package com.bc.ecommerce.infrastructure.db.springdata.query;

import com.bc.ecommerce.application.exception.ProblemsPersistingException;
import com.bc.ecommerce.infrastructure.db.springdata.model.Column;
import com.bc.ecommerce.infrastructure.db.springdata.model.ColumnDerived;
import com.bc.ecommerce.infrastructure.db.springdata.model.ColumnExpression;
import com.bc.ecommerce.infrastructure.db.springdata.model.ColumnSubquery;
import com.bc.ecommerce.infrastructure.db.springdata.model.Projection;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.ap.internal.util.Collections;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Default custom query builder class.
 * In com.bc.ecommerce.infrastructure.db.springdata.query.
 *
 * @author Álvaro Carmona.
 * @since 27/01/2024
 */
@Slf4j
public class DefaultCustomQueryBuilder implements CustomQuery {

  private static final String TEMPLATE_POSITIONAL_PARAM = "?%d";

  private StringBuilder queryBuilder = new StringBuilder();
  private Map<String, String> tableAliases = new HashMap<>();
  private Map<Integer, Object> params = new HashMap<>();
  private Integer position = 0;

  /**
   * Executes the custom query returning the selected result.
   *
   * @param entityManager The entity manager.
   * @param outClass The out class (instance of Projection).
   * @param <T> The Projection subclass.
   * @return The result.
   */
  @Override
  public <T extends Projection> List<T> doQuery(EntityManager entityManager, Class<T> outClass) {
    try {
      return prepare(entityManager, queryBuilder.toString(), Optional.of(outClass)).getResultList();
    } catch (Exception e) {
      throw new ProblemsPersistingException(e.getMessage(), e);
    }

  }

  /**
   * Builds a select column1, column2, ..., columnN from table basic query.
   * Each one of the columns can be specified with a prefix a.column1, b.column2, ..., and so on.
   * The fromTable can be specified with an alias "table alis".
   *
   * @param projection The projection to extract the columns to include in the select (can be prefixed with the alias).
   * @param fromTable The first table appearing in the sql query (and the alias).
   * @param onTheFlyColumns The derived columns whose calculation is determined at runtime.
   * @return This builder.
   */
  public DefaultCustomQueryBuilder select(Projection projection, String fromTable, ColumnDerived... onTheFlyColumns) {
    queryBuilder.append("select ");
    return columns(projection, fromTable, onTheFlyColumns);
  }

  /**
   * Adds the column projections and the from to the query.
   *
   * @param projection The projection containing the static selections.
   * @param fromTable The table where the projection is going to be taken.
   * @param onTheFlyColumns The derived columns whose calculation is known at runtime.
   * @return This builder.
   */
  private DefaultCustomQueryBuilder columns(Projection projection, String fromTable, ColumnDerived... onTheFlyColumns) {

    List<Column> allColumns = Collections.join(projection.getColumns(), Arrays.asList(onTheFlyColumns));

    queryBuilder.append(allColumns.stream()
            .map(this::columnSelectionAsSql).collect(Collectors.joining(", ")));
    queryBuilder.append(" from ");
    queryBuilder.append(table(fromTable));
    return this;
  }

  /**
   * Transform a column selection into the properly sql expression.
   *
   * @param column The column to transform.
   * @return The sql expression.
   */
  private String columnSelectionAsSql(Column column) {
    if (ColumnDerived.class.isAssignableFrom(column.getClass())) {
      if (ColumnExpression.class.equals(column.getClass())) {
        return expressionSelectionAsSql((ColumnExpression) column);
      } else {
        return subquerySelectionAsSql((ColumnSubquery) column);
      }
    } else {
      if (column.isUuidType()) {
        // handing data types that jpa can not handle: uuid.
        return String.format("cast(%s as varchar)", column(column));
      } else {
        return column(column);
      }
    }
  }

  /**
   * Processes an expression column and inserts the proper sql into the query.
   *
   * @param column The expression column.
   * @return The sql generated by the expression column.
   */
  private String expressionSelectionAsSql(ColumnExpression column) {
    String expression = column.getExpression();
    for (Column colExpr : column.getColumnsInExpression()) {
      expression = replaceColumnNameByCompleteName(expression, colExpr);
    }
    if (StringUtils.isNotBlank(column.getName())) {
      return String.format("%s as %s", expression, column.getName());
    } else {
      return expression;
    }
  }

  /**
   * Processes a subquery column and inserts the proper sql into the query.
   *
   * @param column The subquery column.
   * @return The sql generated by the subquery column.
   */
  private String subquerySelectionAsSql(ColumnSubquery column) {
    String subquery = column.getSubquery();
    for (Column colExpr : column.getParentColumns()) {
      subquery = replaceColumnNameByCompleteName(subquery, colExpr);
    }
    return String.format("(%s) as %s", subquery, column.getName());
  }


  /**
   * Finds and replaces the column references with only the column names by the actual column names
   * (having into account the aliases).
   *
   * @param expression The expression.
   * @param column The column.
   * @return The expression with the column name replaced.
   */
  private String replaceColumnNameByCompleteName(String expression, Column column) {
    Pattern pattern = Pattern.compile(String.format("([^a-zA-Z0-9_])(%s)([^a-zA-Z0-9_])", column.getName()));
    Matcher matcher = pattern.matcher(expression);
    if (matcher.find()) {
      expression = matcher.replaceAll(String.format("$1%s$3", column(column)));
    }
    return expression;
  }

  /**
   * Ables to create an between clause given a specific interval
   *
   * @param issueDate The issue date to be applied.
   * @param sqlColumnStart Start date.
   * @param sqlColumnEnd End date.
   * @return The formed clause.
   */
  public String between(String issueDate,  Column sqlColumnStart, Column sqlColumnEnd) {
    return  "'" +
            issueDate +
            "'" +
            " between " +
            column(sqlColumnStart) +
            " and " +
            column(sqlColumnEnd);
  }

  /**
   * Sets the where section.
   * @return This builder.
   */
  public DefaultCustomQueryBuilder where(String where) {
    if (StringUtils.isNotBlank(where)) {
      queryBuilder.append(" where ");
      queryBuilder.append(where);
    }
    return this;
  }

  /**
   * Sets the sort by section.
   *
   * @param sortBy The sort by content.
   * @return This builder.
   */
  public DefaultCustomQueryBuilder sortBy(String sortBy) {
    if (StringUtils.isNotBlank(sortBy)) {
      queryBuilder.append(" order by ");
      queryBuilder.append(sortBy);
      queryBuilder.append(" DESC ");
    }
    return this;
  }

  /**
   * Limit query result
   */
  public DefaultCustomQueryBuilder limit() {
    queryBuilder.append("limit 1");
    return this;
  }

  /**
   * Important: this is done in this way to prevent SQL injection and force the prepared statements.
   *
   * @param value The value to add to the query.
   * @return The positional param.
   */
  public String addParam(Object value) {
    params.put(++position, value);
    return String.format(TEMPLATE_POSITIONAL_PARAM, position);
  }

  /**
   * Creates a simple expression "column = value".
   * @param column The column for the expression.
   * @param value The value for the expression.
   * @return The expression.
   */
  public String eq(Column column, Object value) {
    return value != null ? String.format("%s = %s", column(column), addParam(value)) : null;
  }

  /**
   * Encloses an expression using parentheses.
   *
   * @param expression The expression.
   * @return The expression enclosed with parentheses.
   */
  public String parentheses(String expression) {
    if (StringUtils.isBlank(expression)) {
      return null;
    } else {
      return String.format("(%s)", expression);
    }
  }

  /**
   * Makes a complex expression using each one of the received expressions:
   * expr1 and expr2 and ... and exprN.
   *
   * @param expressions Each one of the expressions that we need to and.
   * @return The complex and expression.
   */
  public String and(String... expressions) {
    return Arrays.asList(expressions).stream()
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.joining(" and "));
  }

  /**
   * Makes a complex expression using each one of the received expressions:
   * expr1 and expr2 and ... and exprN.
   *
   * @param expressions Each one of the expressions that we need to and.
   * @return The complex and expression.
   */
  public String and(List<String> expressions) {
    return expressions.stream()
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.joining(" and "));
  }

  /**
   * Create an alias for the given table.
   * @param table The table.
   * @param alias The alias for the table.
   */
  public void configureTableAlias(String table, String alias) {
    this.tableAliases.put(table, alias);
  }

  /**
   * Adds a table to the query.
   *
   * @param table The table.
   * @return The sql format table.
   */
  private String table(String table) {
    if (tableAliases.containsKey(table)) {
      return String.format("%s %s", table, this.tableAliases.get(table));
    } else {
      return String.format("%s", table);
    }
  }

  /**
   * Adds a column to the query.
   *
   * @param column The column to add.
   * @return The sql-format column.
   */
  private String column(Column column) {
    if (tableAliases.containsKey(column.getTable())) {
      return String.format("%s.%s", this.tableAliases.get(column.getTable()), column.getName());
    } else {
      return String.format("%s", column.getName());
    }
  }

  /**
   * This method creates and prepares the query to be executed. It is important to note that all the
   * user parameters are set via setParameter, but Sonar is unable to infer this.
   *
   * @param em The entity manager.
   * @param preparedSql The prepared sql statement.
   * @param optional The optional result item class.
   * @return The jpa query.
   */
  private Query prepare(EntityManager em, String preparedSql, Optional<Class<?>> optional) {
    log.debug("Prepared query {}. Params {}", preparedSql, this.params);
    return optional
            .map(clazz -> setParams(em.createNativeQuery(preparedSql, clazz)))
            .orElseGet(() -> setParams(em.createNativeQuery(preparedSql)));
  }

  /**
   * Prepares the query with the parameters.
   *
   * @param nativeQuery The parametrized query.
   * @return The prepared query.
   */
  private Query setParams(Query nativeQuery) {
    for (Integer paramPosition : this.params.keySet()) {
      nativeQuery.setParameter(paramPosition, this.params.get(paramPosition));
    }
    return nativeQuery;
  }

}