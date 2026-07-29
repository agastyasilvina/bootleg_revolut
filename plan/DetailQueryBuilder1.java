package com.example.dynamicquery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates a single-round-trip "detail by id" query.
 *
 *   1:1 tables  -> plain LEFT JOIN with aliased columns
 *   1:many      -> LEFT JOIN LATERAL + jsonb_agg / array_agg (no fan-out)
 *   n-tier      -> children nest recursively; each level correlates to its
 *                  PARENT's key, not the root's
 *
 * Filters are structured data with an allow-listed operator and a literal
 * rendered by {@link #literal(Object)} -- the trust boundary. Values must come
 * from deploy-time config, never from a request. Identifiers are validated at
 * build time, so a bad spec fails the deploy rather than a request.
 *
 * PostgreSQL dialect.
 */
public final class DetailQueryBuilder {

    private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern JSON_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    /**
     * Foreign keys that look polymorphic. If one of these is used without a
     * discriminator filter, ids collide ACROSS entity types and you silently
     * serve another entity's rows -- so it is rejected at build time.
     */
    private static final Set<String> GENERIC_FK_NAMES = Set.of(
            "reference_id", "ref_id", "entity_id", "owner_id", "subject_id", "target_id", "parent_id");

    private static final Set<String> SCALAR_TYPES = Set.of(
            "text", "int", "bigint", "numeric", "boolean", "uuid", "date", "timestamptz");

    private DetailQueryBuilder() {
    }

    // ------------------------------------------------------------------
    // Spec model
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

    public record Filter(String column, Op op, Object value) {
        public static Filter eq(String column, Object value) {
            return new Filter(column, Op.EQ, value);
        }

        public static Filter cmp(String column, Op op, Object value) {
            return new Filter(column, op, value);
        }

        public static Filter in(String column, List<?> values) {
            return new Filter(column, Op.IN, values);
        }

        public static Filter isNull(String column) {
            return new Filter(column, Op.IS_NULL, null);
        }

        public static Filter isNotNull(String column) {
            return new Filter(column, Op.IS_NOT_NULL, null);
        }
    }

    /**
     * A 1:1 table. Joins to the root by default, or to another unique join when
     * the chain is multi-hop (application -> person -> edd), so every column
     * lands on the same result row.
     *
     * <p>Extra conditions are rendered into the ON clause, not WHERE. That
     * distinction matters: a LEFT JOIN whose filter sits in WHERE behaves as an
     * inner join and drops rows that have no match.
     *
     * @param parentAlias alias of the table this hangs off; null means the root
     * @param parentKey   key column on that parent; null means the root's pk
     * @param conditions  additional ON predicates, e.g. a polymorphic discriminator
     */
    public record UniqueJoin(String table, String alias, String fk,
                             String parentAlias, String parentKey,
                             List<Column> columns, List<Filter> conditions) {

        /** Hangs off the root. */
        public static UniqueJoin of(String table, String alias, String fk, Column... columns) {
            return new UniqueJoin(table, alias, fk, null, null, List.of(columns), List.of());
        }

        /** Hangs off an earlier join: edd under person. */
        public static UniqueJoin under(String parentAlias, String parentKey,
                                       String table, String alias, String fk, Column... columns) {
            return new UniqueJoin(table, alias, fk, parentAlias, parentKey, List.of(columns), List.of());
        }

        public UniqueJoin on(Filter... extra) {
            return new UniqueJoin(table, alias, fk, parentAlias, parentKey, columns, List.of(extra));
        }

        /**
         * Same contract as {@link Child#polymorphic}: the discriminator is a
         * config-time enum, never a request value. Not needed today -- the
         * reference_type/reference_id pattern is on the 1:many tables only -- but
         * available so the guard below stays satisfiable if that changes.
         */
        public UniqueJoin polymorphic(String typeColumn, Enum<?> type) {
            List<Filter> merged = new java.util.ArrayList<>(conditions);
            merged.add(Filter.eq(typeColumn, type));
            return new UniqueJoin(table, alias, fk, parentAlias, parentKey, columns, List.copyOf(merged));
        }
    }

    /**
     * A 1:many table.
     *
     * @param fk          column on THIS table pointing at the parent's key
     * @param key         this table's own key, used to correlate its nested children
     * @param parentAlias for a TOP-LEVEL child: which table it hangs off. null means
     *                    the root; set it to a UniqueJoin alias to correlate against a
     *                    flattened table instead. Ignored for nested children, which
     *                    always correlate to their enclosing child.
     * @param parentKey   key column on that parent; null means the root's pk
     * @param children    nested one level down (person -> edd); empty for a leaf
     */
    public record Child(
            String jsonKey,
            String table,
            String fk,
            String key,
            List<Column> columns,
            boolean scalar,
            String scalarType,
            String orderBy,
            boolean descending,
            Integer limit,
            List<Filter> filters,
            List<Child> children,
            String parentAlias,
            String parentKey) {

        public static Child objects(String jsonKey, String table, String fk, String key,
                                    String orderBy, Column... columns) {
            return new Child(jsonKey, table, fk, key, List.of(columns), false, null,
                    orderBy, false, null, List.of(), List.of(), null, null);
        }

        public static Child scalars(String jsonKey, String table, String fk,
                                    String column, String scalarType, String orderBy) {
            return new Child(jsonKey, table, fk, null, List.of(Column.of(column)), true, scalarType,
                    orderBy, false, null, List.of(), List.of(), null, null);
        }

        private Child with(List<Filter> f, List<Child> c, Integer lim, boolean desc,
                           String pAlias, String pKey) {
            return new Child(jsonKey, table, fk, key, columns, scalar, scalarType,
                    orderBy, desc, lim, f, c, pAlias, pKey);
        }

        public Child where(Filter... filters) {
            return with(List.of(filters), children, limit, descending, parentAlias, parentKey);
        }

        public Child newestFirst(int limit) {
            return with(filters, children, limit, true, parentAlias, parentKey);
        }

        /**
         * Correlate against a flattened 1:1 join rather than the root, e.g. documents
         * that reference person_tm.person_tm_id when person is joined as "p".
         */
        public Child attachedTo(String parentAlias, String parentKey) {
            return with(filters, children, limit, descending, parentAlias, parentKey);
        }

        /**
         * Polymorphic reference: correlate on the generic id column AND pin the
         * discriminator. The type is a config-time enum, never a request value --
         * see assertDiscriminated().
         */
        public Child polymorphic(String typeColumn, Enum<?> type) {
            List<Filter> merged = new java.util.ArrayList<>(filters);
            merged.add(Filter.eq(typeColumn, type));
            return with(List.copyOf(merged), children, limit, descending, parentAlias, parentKey);
        }

        /** Nest another level: person.having(edd, documents). */
        public Child having(Child... nested) {
            return with(filters, List.of(nested), limit, descending, parentAlias, parentKey);
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

    /** Generated aliases, unique across the whole tree so nested scopes never shadow. */
    private static final class Aliases {
        private int lateral;
        private int source;

        String nextLateral() {
            return "_c" + lateral++;
        }

        String nextSource() {
            return "_s" + source++;
        }
    }

    private record Block(String join, String expression) {
    }

    public static String build(DetailSpec spec) {
        Aliases aliases = new Aliases();
        String root = ident(spec.rootAlias(), "root alias");
        ident(spec.rootTable(), "root table");
        String pk = ident(spec.pk(), "primary key column");

        List<String> select = new ArrayList<>();
        List<String> from = new ArrayList<>();

        for (Column c : spec.rootColumns()) {
            select.add("    %s.%s AS %s".formatted(root, ident(c.column(), "column"), ident(c.alias(), "alias")));
        }

        // Aliases already in scope, so a multi-hop join can be checked at build time.
        Set<String> defined = new LinkedHashSet<>();
        defined.add(root);

        for (UniqueJoin j : spec.uniques()) {
            String a = ident(j.alias(), "join alias");
            String t = ident(j.table(), "join table");
            String fk = ident(j.fk(), "join fk");

            String pa = j.parentAlias() == null ? root : ident(j.parentAlias(), "parent alias");
            String pkey = j.parentKey() == null ? pk : ident(j.parentKey(), "parent key");
            if (!defined.contains(pa)) {
                throw new IllegalArgumentException(
                        "Join '" + a + "' references parent alias '" + pa + "' which is not defined yet; "
                                + "list joins parent-first");
            }
            if (!defined.add(a)) {
                throw new IllegalArgumentException("Duplicate join alias: " + a);
            }

            assertDiscriminated("Join '" + a + "'", fk, j.conditions());

            for (Column c : j.columns()) {
                select.add("    %s.%s AS %s".formatted(a, ident(c.column(), "column"), ident(c.alias(), "alias")));
            }
            String on = "%s.%s = %s.%s".formatted(a, fk, pa, pkey);
            for (Filter f : j.conditions()) {
                on += "\n                AND " + renderFilter(a, f);   // ON, not WHERE
            }
            from.add("LEFT JOIN %s %s ON %s".formatted(t, a, on));
        }

        for (Child child : spec.children()) {
            String cpa = child.parentAlias() == null ? root : ident(child.parentAlias(), "child parent alias");
            String cpk = child.parentKey() == null ? pk : ident(child.parentKey(), "child parent key");
            if (!defined.contains(cpa)) {
                throw new IllegalArgumentException(
                        "Child '" + child.jsonKey() + "' hangs off alias '" + cpa + "' which is not defined");
            }
            Block block = lateral(child, cpa, cpk, aliases);
            // ::text keeps r2dbc-postgresql's Json codec out of the way; native arrays stay native.
            String cast = child.scalar() ? "" : "::text";
            select.add("    %s%s AS %s".formatted(block.expression(), cast, jsonKey(child.jsonKey())));
            from.add(block.join());
        }

        assertNoDuplicateOutputAliases(select);

        return "SELECT\n"
                + String.join(",\n", select) + "\n"
                + "FROM " + spec.rootTable() + " " + root + "\n"
                + (from.isEmpty() ? "" : String.join("\n", from) + "\n")
                + "WHERE " + root + "." + pk + " = :id";
    }

    /**
     * Flattening a chain makes column collisions easy (application.status,
     * person.status, edd.status all want to be "status"). fetch() keys the row
     * map by label, so a collision silently drops one -- catch it at startup.
     */
    private static void assertNoDuplicateOutputAliases(List<String> select) {
        Set<String> seen = new LinkedHashSet<>();
        for (String line : select) {
            String alias = line.substring(line.lastIndexOf(" AS ") + 4).trim();
            if (!seen.add(alias)) {
                throw new IllegalArgumentException("Duplicate output alias: " + alias);
            }
        }
    }

    private static Block lateral(Child child, String parentAlias, String parentKey, Aliases aliases) {
        String la = aliases.nextLateral();
        String s = aliases.nextSource();
        String table = ident(child.table(), "child table");

        assertDiscriminated("Child '" + child.jsonKey() + "'", ident(child.fk(), "child fk"), child.filters());
        if (child.scalar() && !child.children().isEmpty()) {
            throw new IllegalArgumentException("Scalar child cannot nest: " + child.jsonKey());
        }
        if (child.limit() != null && !child.children().isEmpty()) {
            throw new IllegalArgumentException(
                    "limit + nested children is ambiguous on " + child.jsonKey()
                            + "; use a view or DISTINCT ON for latest-per-parent instead");
        }

        List<String> nestedJoins = new ArrayList<>();
        List<String> nestedPairs = new ArrayList<>();
        for (Child nested : child.children()) {
            Block b = lateral(nested, s, ident(child.key(), "child key"), aliases);
            nestedJoins.add(indent(b.join(), "    "));
            nestedPairs.add("'%s', %s".formatted(jsonKey(nested.jsonKey()), b.expression()));
        }

        String direction = child.descending() ? " DESC" : "";
        String orderCol = child.orderBy() == null ? null : ident(child.orderBy(), "order by");

        String join;
        if (child.limit() != null) {
            // Filter/order/limit first, aggregate after: LIMIT on the aggregate would be a no-op.
            String w = aliases.nextSource();
            Set<String> projected = new LinkedHashSet<>();
            for (Column c : child.columns()) {
                projected.add(ident(c.column(), "column"));
            }
            if (orderCol != null) {
                projected.add(orderCol);
            }
            String cols = projected.stream().map(c -> s + "." + c).collect(Collectors.joining(", "));
            String innerOrder = orderCol == null ? "" : "\n        ORDER BY " + s + "." + orderCol + direction;
            String outerOrder = orderCol == null ? "" : " ORDER BY " + w + "." + orderCol + direction;

            join = "LEFT JOIN LATERAL (\n"
                    + "    SELECT " + aggregate(child, w, List.of(), outerOrder) + " AS items\n"
                    + "    FROM (\n"
                    + "        SELECT " + cols + "\n"
                    + "        FROM " + table + " " + s + "\n"
                    + "        WHERE " + predicates(child, s, parentAlias, parentKey) + innerOrder + "\n"
                    + "        LIMIT " + child.limit() + "\n"
                    + "    ) " + w + "\n"
                    + ") " + la + " ON true";
        } else {
            String order = orderCol == null ? "" : " ORDER BY " + s + "." + orderCol + direction;
            List<String> body = new ArrayList<>();
            body.add("    SELECT " + aggregate(child, s, nestedPairs, order) + " AS items");
            body.add("    FROM " + table + " " + s);
            body.addAll(nestedJoins);
            body.add("    WHERE " + predicates(child, s, parentAlias, parentKey));
            join = "LEFT JOIN LATERAL (\n" + String.join("\n", body) + "\n) " + la + " ON true";
        }

        String empty = child.scalar() ? "ARRAY[]::" + scalarType(child.scalarType()) + "[]" : "'[]'::jsonb";
        // *_agg returns NULL, not an empty array, when nothing matches.
        return new Block(join, "COALESCE(%s.items, %s)".formatted(la, empty));
    }

    private static String aggregate(Child child, String alias, List<String> nestedPairs, String order) {
        if (child.scalar()) {
            String col = ident(child.columns().get(0).column(), "column");
            return "array_agg(%s.%s::%s%s)".formatted(alias, col, scalarType(child.scalarType()), order);
        }
        List<String> pairs = new ArrayList<>();
        for (Column c : child.columns()) {
            pairs.add("'%s', %s.%s".formatted(jsonKey(c.alias()), alias, ident(c.column(), "column")));
        }
        pairs.addAll(nestedPairs);
        return "jsonb_agg(jsonb_build_object(\n            "
                + String.join(",\n            ", pairs) + ")" + order + ")";
    }

    private static String predicates(Child child, String s, String parentAlias, String parentKey) {
        List<String> parts = new ArrayList<>();
        parts.add("%s.%s = %s.%s".formatted(s, ident(child.fk(), "child fk"), parentAlias, parentKey));
        for (Filter f : child.filters()) {
            parts.add(renderFilter(s, f));
        }
        return String.join("\n        AND ", parts);
    }

    private static String renderFilter(String alias, Filter f) {
        String col = alias + "." + ident(f.column(), "filter column");
        if (!f.op().takesValue()) {
            return "%s %s".formatted(col, f.op().sql);
        }
        if (f.op() == Op.IN) {
            if (!(f.value() instanceof List<?> values) || values.isEmpty()) {
                throw new IllegalArgumentException("IN filter on " + f.column() + " needs a non-empty list");
            }
            return "%s IN (%s)".formatted(col,
                    values.stream().map(DetailQueryBuilder::literal).collect(Collectors.joining(", ")));
        }
        return "%s %s %s".formatted(col, f.op().sql, literal(f.value()));
    }

    /**
     * A generic-looking foreign key with no discriminator matches ids belonging to
     * OTHER entity types -- silently, since the comparison is just integer to
     * integer. Applies to 1:1 joins exactly as much as to 1:many children.
     */
    private static void assertDiscriminated(String what, String fk, List<Filter> filters) {
        if (GENERIC_FK_NAMES.contains(fk)
                && filters.stream().noneMatch(f -> f.column().endsWith("_type"))) {
            throw new IllegalArgumentException(
                    what + " correlates on generic column '" + fk + "' with no discriminator; "
                            + "add .polymorphic(\"<col>_type\", <Enum>) or ids will collide across entity types");
        }
    }

    private static String indent(String block, String pad) {
        return block.lines().map(l -> l.isEmpty() ? l : pad + l).collect(Collectors.joining("\n"));
    }

    // ------------------------------------------------------------------
    // Validation
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

    /**
     * Renders a config value as a SQL literal. Narrow by design -- anything not
     * listed throws rather than being coerced via toString().
     */
    private static String literal(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Null literal: use isNull()/isNotNull() instead");
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof java.math.BigInteger) {
            return value.toString();
        }
        if (value instanceof java.math.BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof java.time.LocalDate d) {
            return "DATE '" + d + "'";
        }
        if (value instanceof java.time.Instant i) {
            return "TIMESTAMPTZ '" + i + "'";
        }
        if (value instanceof Enum<?> e) {
            return quote(e.name());
        }
        if (value instanceof String s) {
            return quote(s);
        }
        // Float/Double omitted on purpose: text round-tripping loses precision.
        throw new IllegalArgumentException("Unsupported literal type: " + value.getClass().getName());
    }

    private static String quote(String s) {
        if (s.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("NUL byte in literal");
        }
        return "'" + s.replace("'", "''") + "'";   // standard_conforming_strings is on by default
    }

    // ------------------------------------------------------------------
    // Example: application -> person -> edd
    // ------------------------------------------------------------------

    /**
     * Discriminator values for the polymorphic tables. An enum, not a String and
     * not a method argument: which type a child pins is determined by where it
     * sits in the spec, so it must never come from a request.
     *
     * The constant name is what gets written into the SQL, so it has to match the
     * value stored in reference_type exactly.
     */
    public enum RefType {
        APPLICATION, PERSON, COMPANY
    }

    public static DetailSpec exampleSpec() {
        // 1:many, keyed to the APPLICATION by generic reference
        Child notes = Child.objects("notes", "application_note", "reference_id", "note_id", "created_at",
                        Column.of("note_id", "noteId"),
                        Column.of("body", "body"),
                        Column.of("created_at", "createdAt"))
                .polymorphic("reference_type", RefType.APPLICATION)
                .where(Filter.isNull("deleted_at"))
                .newestFirst(20);

        // 1:many, keyed to the flattened PERSON row -- note attachedTo("p", ...)
        Child personDocs = Child.objects("personDocuments", "document", "reference_id", "document_id", "uploaded_at",
                        Column.of("document_id", "documentId"),
                        Column.of("document_url", "documentUrl"),
                        Column.of("doc_kind", "docKind"))
                .attachedTo("p", "person_tm_id")
                .polymorphic("reference_type", RefType.PERSON)
                .where(Filter.in("doc_kind", List.of("PASSPORT", "PROOF_OF_ADDRESS")));

        // scalar array, same polymorphic pattern -> List<String>
        Child personTags = Child.scalars("personTags", "entity_tag", "reference_id",
                        "tag", "text", "tag")
                .attachedTo("p", "person_tm_id")
                .polymorphic("reference_type", RefType.PERSON);

        return new DetailSpec(
                "applicationDetail",
                "application",
                "t1",
                "app_id",
                List.of(Column.of("app_id"), Column.of("status", "application_status")),
                List.of(
                        UniqueJoin.of("foo", "f", "app_id", Column.of("a", "foo_a")),
                        UniqueJoin.of("bar", "b", "app_id", Column.of("b", "bar_b")),
                        // one hop off the root...
                        UniqueJoin.of("person_tm", "p", "app_id",
                                Column.of("person_tm_id", "person_id"),
                                Column.of("full_name", "person_name"),
                                Column.of("status", "person_status")),
                        // ...and one hop off that
                        UniqueJoin.under("p", "person_tm_id", "edd", "e", "person_tm_id",
                                Column.of("risk_level", "edd_risk_level"),
                                Column.of("reviewed_at", "edd_reviewed_at"),
                                Column.of("status", "edd_status"))),
                List.of(notes, personDocs, personTags));
    }

    public static void main(String[] args) {
        System.out.println(build(exampleSpec()));
    }
}
