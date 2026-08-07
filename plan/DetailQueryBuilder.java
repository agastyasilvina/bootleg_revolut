package com.example.dynamicquery;

import java.util.LinkedHashMap;
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
 * The SPEC is dynamic. The SQL is not: every identifier is validated here, at
 * build time (i.e. application startup), and the only runtime bind is :id.
 * Nothing from an HTTP request ever reaches the statement text.
 *
 * PostgreSQL dialect.
 */
public final class DetailQueryBuilder {

    /** Unquoted lower-case identifiers only. Anything else is rejected outright. */
    private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** JSON keys end up inside a SQL string literal, so no quotes are permitted. */
    private static final Pattern JSON_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    /** Allow-list for the ::type cast on scalar arrays. */
    private static final Set<String> SCALAR_TYPES = Set.of(
            "text", "int", "bigint", "numeric", "boolean", "uuid", "date", "timestamptz");

    private DetailQueryBuilder() {
    }

    // ------------------------------------------------------------------
    // Spec model  (populate from YAML via @ConfigurationProperties, a config
    // class, or a DB table -- all fine, because none of it is SQL)
    // ------------------------------------------------------------------

    /** A physical column and the name it should appear under in the result. */
    public record Column(String column, String alias) {
        public static Column of(String column, String alias) {
            return new Column(column, alias);
        }

        public static Column of(String column) {
            return new Column(column, column);
        }
    }

    /** A 1:1 table joined to the root on {@code fk}. */
    public record UniqueJoin(String table, String alias, String fk, List<Column> columns) {
    }

    /** A 1:many table, aggregated in its own LATERAL scope. */
    public record Child(
            String jsonKey,
            String table,
            String fk,
            List<Column> columns,
            boolean scalar,
            String scalarType,
            String orderBy) {

        /** Multi-column child -> jsonb array of objects -> List&lt;SomeDto&gt;. */
        public static Child objects(String jsonKey, String table, String fk,
                                    String orderBy, Column... columns) {
            return new Child(jsonKey, table, fk, List.of(columns), false, null, orderBy);
        }

        /** Single-column child -> native array -> List&lt;String&gt; / String[]. */
        public static Child scalars(String jsonKey, String table, String fk,
                                    String column, String scalarType, String orderBy) {
            return new Child(jsonKey, table, fk, List.of(Column.of(column)), true, scalarType, orderBy);
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

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    public static String build(DetailSpec spec) {
        String root = ident(spec.rootAlias(), "root alias");
        ident(spec.rootTable(), "root table");
        ident(spec.pk(), "primary key column");

        List<String> select = new java.util.ArrayList<>();
        List<String> from = new java.util.ArrayList<>();

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
            from.add("LEFT JOIN %s %s ON %s.%s = %s.%s".formatted(t, a, a, fk, root, spec.pk()));
        }

        int i = 0;
        for (Child child : spec.children()) {
            String lateralAlias = "_c" + i++;               // generated: cannot collide with user aliases
            select.add("    " + selectExpr(child, lateralAlias));
            from.add(lateral(child, lateralAlias, root, spec.pk()));
        }

        return "SELECT\n"
                + String.join(",\n", select) + "\n"
                + "FROM " + spec.rootTable() + " " + root + "\n"
                + (from.isEmpty() ? "" : String.join("\n", from) + "\n")
                + "WHERE " + root + "." + spec.pk() + " = :id";
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

    private static String lateral(Child child, String lateralAlias, String root, String pk) {
        String table = ident(child.table(), "child table");
        String fk = ident(child.fk(), "child fk");
        String s = "_s";
        String order = child.orderBy() == null ? "" : " ORDER BY " + s + "." + ident(child.orderBy(), "order by");

        String agg;
        if (child.scalar()) {
            String col = ident(child.columns().get(0).column(), "column");
            agg = "array_agg(%s.%s::%s%s)".formatted(s, col, scalarType(child.scalarType()), order);
        } else {
            String pairs = child.columns().stream()
                    .map(c -> "'%s', %s.%s".formatted(jsonKey(c.alias()), s, ident(c.column(), "column")))
                    .collect(Collectors.joining(", "));
            agg = "jsonb_agg(jsonb_build_object(%s)%s)".formatted(pairs, order);
        }

        return """
                LEFT JOIN LATERAL (
                    SELECT %s AS items
                    FROM %s %s
                    WHERE %s.%s = %s.%s
                ) %s ON true""".formatted(agg, table, s, s, fk, root, pk, lateralAlias);
    }

    // ------------------------------------------------------------------
    // Validation -- runs at startup, so a bad spec fails the deploy, not a request
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

    private static String scalarType(String value) {
        if (value == null || !SCALAR_TYPES.contains(value)) {
            throw new IllegalArgumentException("Unsupported scalar type: " + value);
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Example: this produces exactly the query shape from the conversation
    // ------------------------------------------------------------------

    public static DetailSpec exampleSpec() {
        return new DetailSpec(
                "applicationDetail",
                "application",
                "t1",
                "app_id",
                List.of(Column.of("app_id"), Column.of("status")),
                List.of(
                        new UniqueJoin("foo", "foo", "app_id", List.of(Column.of("a", "foo_a"))),
                        new UniqueJoin("bar", "bar", "app_id", List.of(Column.of("b", "bar_b"))),
                        new UniqueJoin("baz", "baz", "app_id", List.of(Column.of("c", "baz_c")))),
                List.of(
                        Child.objects("non_foo_x", "non_foo", "app_id", "id",
                                Column.of("column_a", "columnA"),
                                Column.of("column_b", "columnB")),
                        Child.scalars("document_urls", "table4", "application_id",
                                "document_url", "text", "id")));
    }

    public static void main(String[] args) {
        System.out.println(build(exampleSpec()));
    }
}
