package com.example.dynamicquery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates a single-round-trip "detail by id" query:
 *
 *   - 1:1 tables  -> plain LEFT JOIN with aliased columns
 *   - 1:many      -> LEFT JOIN LATERAL + jsonb_agg / array_agg (no fan-out)
 *
 * Children support filters, ordering and LIMIT. Filters are structured data:
 * an allow-listed operator plus a BOUND value. No caller-supplied text ever
 * reaches the statement. Identifiers are validated at build time (startup),
 * so a bad spec fails the deploy rather than a request.
 *
 * PostgreSQL dialect.
 */
public final class DetailQueryBuilder {

    /** Unquoted lower-case identifiers only. Anything else is rejected outright. */
    private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** JSON keys end up inside a SQL string literal, so no quotes are permitted. */
    private static final Pattern JSON_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    /** Bind-parameter names are rendered into the SQL, so they are validated too. */
    private static final Pattern PARAM = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    /** Allow-list for the ::type cast on scalar arrays. */
    private static final Set<String> SCALAR_TYPES = Set.of(
            "text", "int", "bigint", "numeric", "boolean", "uuid", "date", "timestamptz");

    private DetailQueryBuilder() {
    }

    // ------------------------------------------------------------------
    // Spec model  (populate from YAML via @ConfigurationProperties, a config
    // class, or a DB table -- all fine, because none of it is SQL)
    // ------------------------------------------------------------------

    public record Column(String column, String alias) {
        public static Column of(String column, String alias) {
            return new Column(column, alias);
        }

        public static Column of(String column) {
            return new Column(column, column);
        }
    }

    public enum Op {
        EQ("="), NEQ("<>"), LT("<"), LTE("<="), GT(">"), GTE(">="),
        LIKE("LIKE"), IN("IN"), IS_NULL("IS NULL"), IS_NOT_NULL("IS NOT NULL");

        final String sql;

        Op(String sql) {
            this.sql = sql;
        }

        boolean takesValue() {
            return this != IS_NULL && this != IS_NOT_NULL;
        }
    }

    /**
     * A child-row filter. Exactly one of {@code literal} / {@code param} is set:
     *   literal -> value comes from config, bound under a generated name
     *   param   -> value is supplied per request, bound by the caller
     */
    public record Filter(String column, Op op, Object literal, String param) {

        /** Config-time constant, e.g. status = 'ACTIVE'. */
        public static Filter eq(String column, Object value) {
            return new Filter(column, Op.EQ, value, null);
        }

        public static Filter cmp(String column, Op op, Object value) {
            return new Filter(column, op, value, null);
        }

        /** Config-time IN list. */
        public static Filter in(String column, List<?> values) {
            return new Filter(column, Op.IN, values, null);
        }

        public static Filter isNull(String column) {
            return new Filter(column, Op.IS_NULL, null, null);
        }

        public static Filter isNotNull(String column) {
            return new Filter(column, Op.IS_NOT_NULL, null, null);
        }

        /** Value supplied at request time; caller must bind this name. */
        public static Filter param(String column, Op op, String paramName) {
            return new Filter(column, op, null, paramName);
        }
    }

    public record UniqueJoin(String table, String alias, String fk, List<Column> columns) {
    }

    public record Child(
            String jsonKey,
            String table,
            String fk,
            List<Column> columns,
            boolean scalar,
            String scalarType,
            String orderBy,
            boolean descending,
            Integer limit,
            List<Filter> filters) {

        /** Multi-column child -> jsonb array of objects -> List&lt;SomeDto&gt;. */
        public static Child objects(String jsonKey, String table, String fk,
                                    String orderBy, Column... columns) {
            return new Child(jsonKey, table, fk, List.of(columns), false, null,
                    orderBy, false, null, List.of());
        }

        /** Single-column child -> native array -> List&lt;String&gt; / String[]. */
        public static Child scalars(String jsonKey, String table, String fk,
                                    String column, String scalarType, String orderBy) {
            return new Child(jsonKey, table, fk, List.of(Column.of(column)), true, scalarType,
                    orderBy, false, null, List.of());
        }

        public Child where(Filter... filters) {
            return new Child(jsonKey, table, fk, columns, scalar, scalarType,
                    orderBy, descending, limit, List.of(filters));
        }

        public Child newestFirst(int limit) {
            return new Child(jsonKey, table, fk, columns, scalar, scalarType,
                    orderBy, true, limit, filters);
        }
    }

    public record DetailSpec(
            String name,
            String rootTable,
            String rootAlias,
            String pk,
            List<Column> rootColumns,
            List<UniqueJoin> uniques,
            List<Child> children) {
    }

    /**
     * Result of generation. {@code binds} are config-derived values the registry
     * applies for you; {@code runtimeParams} are names the caller must supply.
     */
    public record Generated(String sql, Map<String, Object> binds, Set<String> runtimeParams) {
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    public static Generated build(DetailSpec spec) {
        String root = ident(spec.rootAlias(), "root alias");
        ident(spec.rootTable(), "root table");
        String pk = ident(spec.pk(), "primary key column");

        List<String> select = new ArrayList<>();
        List<String> from = new ArrayList<>();
        Map<String, Object> binds = new LinkedHashMap<>();
        Set<String> runtimeParams = new LinkedHashSet<>();

        for (Column c : spec.rootColumns()) {
            select.add("    %s.%s AS %s".formatted(root, ident(c.column(), "column"), ident(c.alias(), "alias")));
        }

        for (UniqueJoin j : spec.uniques()) {
            String a = ident(j.alias(), "join alias");
            String t = ident(j.table(), "join table");
            String fk = ident(j.fk(), "join fk");
            for (Column c : j.columns()) {
                select.add("    %s.%s AS %s".formatted(a, ident(c.column(), "column"), ident(c.alias(), "alias")));
            }
            from.add("LEFT JOIN %s %s ON %s.%s = %s.%s".formatted(t, a, a, fk, root, pk));
        }

        int i = 0;
        for (Child child : spec.children()) {
            String lateralAlias = "_c" + i++;          // generated: cannot collide with user aliases
            select.add("    " + selectExpr(child, lateralAlias));
            from.add(lateral(child, lateralAlias, root, pk, binds, runtimeParams));
        }

        String sql = "SELECT\n"
                + String.join(",\n", select) + "\n"
                + "FROM " + spec.rootTable() + " " + root + "\n"
                + (from.isEmpty() ? "" : String.join("\n", from) + "\n")
                + "WHERE " + root + "." + pk + " = :id";

        return new Generated(sql, Map.copyOf(binds), Set.copyOf(runtimeParams));
    }

    private static String selectExpr(Child child, String lateralAlias) {
        String key = jsonKey(child.jsonKey());
        if (child.scalar()) {
            String type = scalarType(child.scalarType());
            // COALESCE matters: *_agg returns NULL, not an empty array, when no rows match.
            return "COALESCE(%s.items, ARRAY[]::%s[]) AS %s".formatted(lateralAlias, type, key);
        }
        return "COALESCE(%s.items, '[]'::jsonb)::text AS %s".formatted(lateralAlias, key);
    }

    private static String lateral(Child child, String lateralAlias, String root, String pk,
                                  Map<String, Object> binds, Set<String> runtimeParams) {
        String table = ident(child.table(), "child table");
        String fk = ident(child.fk(), "child fk");
        String s = "_s";

        String direction = child.descending() ? " DESC" : "";
        String order = child.orderBy() == null
                ? ""
                : " ORDER BY " + s + "." + ident(child.orderBy(), "order by") + direction;

        // Filters + LIMIT need their own scope, otherwise LIMIT would apply after aggregation.
        boolean windowed = child.limit() != null;
        String source = windowed
                ? innerSelect(child, s, root, pk, fk, order, binds, runtimeParams, lateralAlias)
                : "%s %s\n    WHERE %s".formatted(table, s,
                        predicates(child, s, root, pk, fk, binds, runtimeParams, lateralAlias));

        String agg = aggregate(child, s, windowed ? "" : order);

        return """
                LEFT JOIN LATERAL (
                    SELECT %s AS items
                    FROM %s
                ) %s ON true""".formatted(agg, source, lateralAlias);
    }

    /** Sub-select used when LIMIT is present: filter+order+limit first, aggregate after. */
    private static String innerSelect(Child child, String s, String root, String pk, String fk,
                                      String order, Map<String, Object> binds,
                                      Set<String> runtimeParams, String lateralAlias) {
        String cols = child.columns().stream()
                .map(c -> s + "." + ident(c.column(), "column"))
                .collect(Collectors.joining(", "));
        return """
                (
                        SELECT %s
                        FROM %s %s
                        WHERE %s%s
                        LIMIT %d
                    ) %s""".formatted(cols, ident(child.table(), "child table"), s,
                predicates(child, s, root, pk, fk, binds, runtimeParams, lateralAlias),
                order, child.limit(), s);
    }

    private static String aggregate(Child child, String s, String order) {
        if (child.scalar()) {
            String col = ident(child.columns().get(0).column(), "column");
            return "array_agg(%s.%s::%s%s)".formatted(s, col, scalarType(child.scalarType()), order);
        }
        String pairs = child.columns().stream()
                .map(c -> "'%s', %s.%s".formatted(jsonKey(c.alias()), s, ident(c.column(), "column")))
                .collect(Collectors.joining(", "));
        return "jsonb_agg(jsonb_build_object(%s)%s)".formatted(pairs, order);
    }

    private static String predicates(Child child, String s, String root, String pk, String fk,
                                     Map<String, Object> binds, Set<String> runtimeParams,
                                     String lateralAlias) {
        List<String> parts = new ArrayList<>();
        parts.add("%s.%s = %s.%s".formatted(s, fk, root, pk));   // the correlation, always present

        int p = 0;
        for (Filter f : child.filters()) {
            String col = s + "." + ident(f.column(), "filter column");
            if (!f.op().takesValue()) {
                parts.add("%s %s".formatted(col, f.op().sql));
                continue;
            }
            String name;
            if (f.param() != null) {
                name = param(f.param());
                runtimeParams.add(name);
            } else {
                name = lateralAlias + "_p" + p++;
                binds.put(name, f.literal());
            }
            parts.add(f.op() == Op.IN
                    ? "%s IN (:%s)".formatted(col, name)
                    : "%s %s :%s".formatted(col, f.op().sql, name));
        }
        return String.join("\n      AND ", parts);
    }

    // ------------------------------------------------------------------
    // Validation -- runs at startup, so a bad spec fails the deploy
    // ------------------------------------------------------------------

    private static String ident(String value, String what) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Illegal " + what + ": " + value);
        }
        return value;
    }

    private static String jsonKey(String value) {
        if (value == null || !JSON_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("Illegal JSON key: " + value);
        }
        return value;
    }

    private static String param(String value) {
        if (value == null || !PARAM.matcher(value).matches()) {
            throw new IllegalArgumentException("Illegal parameter name: " + value);
        }
        return value;
    }

    private static String scalarType(String value) {
        if (value == null || !SCALAR_TYPES.contains(value)) {
            throw new IllegalArgumentException("Unsupported scalar type: " + value);
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Example
    // ------------------------------------------------------------------

    public static DetailSpec exampleSpec() {
        return new DetailSpec(
                "applicationDetail",
                "application",
                "t1",
                "app_id",
                List.of(Column.of("app_id"), Column.of("status")),
                List.of(
                        new UniqueJoin("foo", "f", "app_id", List.of(Column.of("a", "foo_a"))),
                        new UniqueJoin("bar", "b", "app_id", List.of(Column.of("b", "bar_b"))),
                        new UniqueJoin("baz", "z", "app_id", List.of(Column.of("c", "baz_c")))),
                List.of(
                        // filtered child: only live rows, of a type chosen per request
                        Child.objects("non_foo_x", "non_foo", "app_id", "id",
                                        Column.of("column_a", "columnA"),
                                        Column.of("column_b", "columnB"))
                                .where(Filter.eq("status", "ACTIVE"),
                                        Filter.isNull("deleted_at"),
                                        Filter.param("doc_type", Op.EQ, "docType")),
                        // top-N child: ten most recent, newest first
                        Child.objects("audit_logs", "audit_log", "application_id", "created_at",
                                        Column.of("action", "action"),
                                        Column.of("created_at", "createdAt"))
                                .newestFirst(10),
                        // scalar child with a config-time IN list
                        Child.scalars("document_urls", "table4", "application_id",
                                        "document_url", "text", "id")
                                .where(Filter.in("category", List.of("KYC", "CONTRACT")))));
    }

    public static void main(String[] args) {
        Generated g = build(exampleSpec());
        System.out.println(g.sql());
        System.out.println("\n-- config binds : " + g.binds());
        System.out.println("-- caller binds : " + g.runtimeParams());
    }
}
