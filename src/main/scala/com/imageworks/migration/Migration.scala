/*
 * Copyright (c) 2009 Sony Pictures Imageworks
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the
 * distribution.  Neither the name of Sony Pictures Imageworks nor the
 * names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.imageworks.migration

import net.sf.log4jdbc.ConnectionSpy
import org.slf4j.LoggerFactory

/**
 * Due to the JVM erasure, the scala.Predef.ArrowAssoc.->
 * method generates a Tuple2 and the following cannot be distinguished
 *
 *   "table_name" -> "column1"
 *
 *   "table_name" -> ("column1", "column2")
 *
 * After erasure a Tuple2[String,String] is identical to a
 * Tuple2[String,Tuple2[String,String]].  So to work around this, the
 * -> operator is redefined to operate only on String's, which
 * effectively removes the type from the first type of the Tuple2 and
 * allows it to be overloaded on the second type of the Tuple2.  The
 * MigrationArrowAssoc class has the new -> method.
 */
class MigrationArrowAssoc(s : String)
{
  def `->`(other : String) : TableColumnDefinition =
  {
    new TableColumnDefinition(s, Array(other))
  }

  def `->`(other : Tuple2[String,String]) : TableColumnDefinition =
  {
    new TableColumnDefinition(s, Array(other._1, other._2))
  }
}

abstract class Migration
{
  private final
  val logger = LoggerFactory.getLogger(this.getClass)

  /**
   * Concrete migration classes must define this method to migrate the
   * database up to a new migration.
   */
  def up() : Unit

  /**
   * Concrete migration classes must define this method to back out of
   * this migration.  If the migration cannot be reversed, then a
   * IrreversibleMigrationException should be thrown.
   */
  def down() : Unit

  /**
   * The raw connection to the database that underlies the
   * ConnectionSpy connection.  This is provided in case the real
   * database connection is needed because the ConnectionSpy
   * connection does not provide a required feature.  This connection
   * should not be used in normal use.
   *
   * This is set using property style dependency injection instead of
   * constructor style injection, which makes for cleaner code for the
   * users of this migration framework.
   */
  private[migration] var raw_connection_ : java.sql.Connection = _

  /**
   * Get the raw connection to the database the migration can use for
   * any custom work.  This connection is the raw connection that
   * underlies the logging connection and does not log any operations
   * performed on it.  It should only be used when the ConnectionSpy
   * connection does not provide a required feature.  The Migration
   * subclass must be careful with this connection and leave it in a
   * good state, as all of the other migration methods defined in
   * Migration use the same connection.
   */
  def rawConnection = raw_connection_

  /**
   * The connection to the database that is used for the migration.
   * This connection also logs all operations performed on it.
   *
   * This is set using property style dependency injection instead of
   * constructor style injection, which makes for cleaner code for the
   * users of this migration framework.
   */
  private[migration] var connection_ : net.sf.log4jdbc.ConnectionSpy = _

  /**
   * Get the connection to the database the migration can use for any
   * custom work.  This connection logs all operations performed on
   * it.  The Migration subclass must be careful with this connection
   * and leave it in a good state, as all of the other migration
   * methods defined in Migration use the same connection.
   */
  def connection = connection_

  /**
   * The database adapter that will be used for the migration.
   *
   * This is set using property style dependency injection instead of
   * constructor style injection, which makes for cleaner code for the
   * users of this migration framework.
   */
  private[migration] var adapter_ : DatabaseAdapter = _

  /**
   * Override the -> implicit definition to create a
   * MigrationArrowAssoc instead of a scala.Predef.ArrowAssoc.  See
   * the above comment on the MigrationArrowAssoc class why this is
   * done.
   */
  implicit def stringToMigrationArrowAssoc(s : String) : MigrationArrowAssoc =
  {
    new MigrationArrowAssoc(s)
  }

  /**
   * Convert a table and column name definition into a On foreign key
   * instance.
   */
  def on(definition : TableColumnDefinition) : On =
  {
    new On(definition)
  }

  /**
   * Convert a table and column name definition into a References
   * foreign key instance.
   */
  def references(definition : TableColumnDefinition) : References =
  {
    new References(definition)
  }

  final
  def execute(sql : String) : Unit =
  {
    val statement = connection_.createStatement
    try {
      statement.execute(sql)
    }
    finally {
      statement.close()
    }
  }

  /**
   * Given a SQL string and a Function1[java.sql.PreparedStatement,Unit],
   * start a new transaction by turning off auto-commit mode on the
   * connection then create a new prepared statement with the SQL
   * string and pass the prepared statement to the closure argument.
   * The closure should not perform the commit as this method will
   * commit the transaction.  If the closure throws an exception then
   * the transaction is rolled back and the exception that caused the
   * rollback is re-thrown.  Finally, the auto-commit state is reset
   * to the value the connection had before this method was called.
   *
   * @param sql the SQL text that will be prepared
   * @param f the Function1[java.sql.PreparedStatement,Unit] that will
   *        be given a new prepared statement
   */
  final
  def withPreparedStatement(sql : String)
                           (f : java.sql.PreparedStatement => Unit) : Unit =
  {
    val c = connection_
    val auto_commit = c.getAutoCommit
    try {
      c.setAutoCommit(false)
      val statement = c.prepareStatement(sql)
      try {
        f(statement)
        c.commit()
      }
      catch {
        case e1 => {
          try {
            c.rollback()
          }
          catch {
            case e2 =>
              logger.warn("Trying to rollback a transaction due to " +
                          e1 +
                          " failed and threw:",
                          e2)
          }
          throw e1
        }
      }
      finally {
        statement.close()
      }
    }
    finally {
      c.setAutoCommit(auto_commit)
    }
  }

  /**
   * Given a SQL result set and a Function1[java.sql.ResultSet,R],
   * pass the result set to the closure.  After the closure has
   * completed, either normally via a return or by throwing an
   * exception, close the result set.
   *
   * @param rs the SQL result set
   * @param f the Function1[java.sql.ResultSet,R] that will be given
   *        the result set
   * @return the result of f if f returns normally
   */
  final
  def withResultSet[R](rs : java.sql.ResultSet)
                      (f : java.sql.ResultSet => R) : R =
  {
    With.resultSet(rs)(f)
  }

  final
  def createTable(table_name : String,
                  options : TableOption*)
                 (body : TableDefinition => Unit) : Unit =
  {
    val table_definition = new TableDefinition(adapter_, table_name)

    body(table_definition)

    val sql = new java.lang.StringBuilder(512)
                .append("CREATE TABLE ")
                .append(adapter_.quoteTableName(table_name))
                .append(" (")
                .append(table_definition.toSql)
                .append(')')
                .toString
    execute(sql)
  }

  final
  def dropTable(table_name : String) : Unit =
  {
    val sql = new java.lang.StringBuilder(512)
                .append("DROP TABLE ")
                .append(adapter_.quoteTableName(table_name))
                .toString
    execute(sql)
  }

  private
  def indexNameFor(table_name : String,
                   column_names : Array[String],
                   options : IndexOption*) : Tuple2[String,List[IndexOption]] =
  {
    var opts = options.toList

    var index_name_opt : Option[String] = None

    for (opt @ Name(name) <- opts) {
      opts -= opt
      if (index_name_opt.isDefined && index_name_opt.get != name) {
        logger.warn("Redefining the index name from '{}' to '{}'.",
                    index_name_opt.get +
                    name)
      }
      index_name_opt = Some(name)
    }

    val name = index_name_opt.getOrElse {
                 "idx_" +
                 table_name +
                 "_" +
                 column_names.mkString("_")
               }

    (name, opts)
  }

  /**
   * Add an index to a table on a non-empty list of column names.  The
   * name of the index is automatically generated unless Name() is
   * given as an option.
   *
   * @param table_name the table to add the index to
   * @param column_names a list of one or more column names that the
   *        index will be on
   * @param options a possibly empty list of index options to
   *        customize the creation of the index
   */
  final
  def addIndex(table_name : String,
               column_names : Array[String],
               options : IndexOption*) : Unit =
  {
    if (column_names.isEmpty) {
      throw new IllegalArgumentException("Adding an index requires at " +
                                         "least one column name.")
    }

    var (name, opts) = indexNameFor(table_name, column_names, options : _*)

    var unique = false
    for (option @ Unique <- opts) {
      opts -= option
      unique = true
    }

    val quoted_column_names = column_names.map {
                                adapter_.quoteColumnName(_)
                              }.mkString(", ")

    val sql = new java.lang.StringBuilder(512)
               .append("CREATE ")
               .append(if (unique) "UNIQUE " else "")
               .append("INDEX ")
               .append(adapter_.quoteColumnName(name))
               .append(" ON ")
               .append(adapter_.quoteTableName(table_name))
               .append(" (")
               .append(quoted_column_names)
               .append(")")
               .toString

    execute(sql)
  }

  /**
   * Add an index to a table on a column.  The name of the index is
   * automatically generated unless Name() is given as an option.
   *
   * @param table_name the table to add the index to
   * @param column_name the name of the column that the index will be
   *        on
   * @param options a possibly empty list of index options to
   *        customize the creation of the index
   */
  final
  def addIndex(table_name : String,
               column_name : String,
               options : IndexOption*) : Unit =
  {
    addIndex(table_name, Array(column_name), options : _*)
  }

  /**
   * Remove an index on a table that is composed of a non-empty list
   * of column names.  The name of the index to remove is
   * automatically generated unless Name() is given as an option.
   *
   * @param table_name the table to remove the index from
   * @param column_names a list of one or more column names that the
   *        index is on
   * @param options a possibly empty list of index options to
   *        customize the removal of the index
   */
  final
  def removeIndex(table_name : String,
                  column_names : Array[String],
                  options : Name*) : Unit =
  {
    if (column_names.isEmpty) {
      throw new IllegalArgumentException("Removing an index requires at " +
                                         "least one column name.")
    }

    val (name, opts) = indexNameFor(table_name, column_names, options : _*)

    val sql = adapter_.removeIndexSql(table_name, name)

    execute(sql)
  }

  /**
   * Remove an index on a column in a table.  The name of the index to
   * remove is automatically generated unless Name() is given as an
   * option.
   *
   * @param table_name the table to remove the index from
   * @param column_name the name of the column the index is on
   * @param options a possibly empty list of index options to
   *        customize the removal of the index
   */
  final
  def removeIndex(table_name : String,
                  column_name : String,
                  options : Name*) : Unit =
  {
    removeIndex(table_name, Array(column_name), options : _*)
  }

  /**
   * Given a foreign key relationship, create a name for it, using a
   * Name() if it is provided in the options.
   *
   * @param on the table and columns the foreign key constraint is on
   * @param references the table and columns the foreign key
   *        constraint references
   * @options a varargs list of ForeignKeyOption's
   * @return a Tuple2 with the calculated name or the overridden name
   *         from a Name and the remaining options
   */
  private
  def foreignKeyNameFor
    (on : On,
     references : References,
     options : ForeignKeyOption*) : Tuple2[String,List[ForeignKeyOption]] =
  {
    var opts = options.toList

    var fk_name_opt : Option[String] = None

    for (opt @ Name(name) <- opts) {
      opts -= opt
      if (fk_name_opt.isDefined && fk_name_opt.get != name) {
        logger.warn("Redefining the foreign key name from '{}'' to '{}'.",
                    fk_name_opt.get,
                    name)
      }
      fk_name_opt = Some(name)
    }

    val name = fk_name_opt.getOrElse {
                 "fk_" +
                 on.tableName +
                 "_" +
                 on.columnNames.mkString("_") +
                 "_" +
                 references.tableName +
                 "_" +
                 references.columnNames.mkString("_")
               }

    (name, opts)
  }

  /**
   * Add a foreign key to a table.  The name of the foreign key is
   * automatically generated unless Name() is given as an option.
   *
   * @param on the table and column name(s) to place the foreign key
   *        on
   * @param references the table and column name(s) that the foreign
   *        key references
   * @param options a possibly empty list of foreign key options to
   *        customize the creation of the foreign key
   */
  def addForeignKey(on : On,
                    references : References,
                    options : ForeignKeyOption*) : Unit =
  {
    if (on.columnNames.length == 0) {
      throw new IllegalArgumentException("Adding a foreign key constraint " +
                                         "requires at least one column name " +
                                         "in the table adding the constraint.")
    }

    if (references.columnNames.length == 0) {
      throw new IllegalArgumentException("Adding a foreign key constraint " +
                                         "requires at least one column name " +
                                         "from the table being referenced.")
    }

    var (name, opts) = foreignKeyNameFor(on, references, options : _*)

    val quoted_on_column_names = on.columnNames.map {
                                   adapter_.quoteColumnName(_)
                                 }.mkString(", ")

    val quoted_references_column_names = references.columnNames.map {
                                           adapter_.quoteColumnName(_)
                                         }.mkString(", ")

    var on_delete_opt : Option[OnDelete] = None

    for (opt @ OnDelete(action) <- opts) {
      if (on_delete_opt.isDefined && action != on_delete_opt.get.action) {
        logger.warn("Overriding the ON DELETE action from '{}' to '{}'.",
                    on_delete_opt.get.action,
                    action)
      }
      opts -= opt
      on_delete_opt = Some(opt)
    }

    var on_update_opt : Option[OnUpdate] = None

    for (opt @ OnUpdate(action) <- opts) {
      if (on_update_opt.isDefined && action != on_update_opt.get.action) {
        logger.warn("Overriding the ON UPDATE action from '{}' to '{}'.",
                    on_update_opt.get.action,
                    action)
      }
      opts -= opt
      on_update_opt = Some(opt)
    }

    val sql = new java.lang.StringBuilder(512)
               .append("ALTER TABLE ")
               .append(adapter_.quoteTableName(on.tableName))
               .append(" ADD CONSTRAINT ")
               .append(name)
               .append(" FOREIGN KEY (")
               .append(quoted_on_column_names)
               .append(") REFERENCES ")
               .append(adapter_.quoteTableName(references.tableName))
               .append(" (")
               .append(quoted_references_column_names)
               .append(")")

    val on_delete_sql = adapter_.onDeleteSql(on_delete_opt)
    if (! on_delete_sql.isEmpty) {
      sql.append(' ')
         .append(on_delete_sql)
    }

    val on_update_sql = adapter_.onUpdateSql(on_update_opt)
    if (! on_update_sql.isEmpty) {
      sql.append(' ')
         .append(on_update_sql)
    }

    execute(sql.toString)
  }

  /**
   * Add a foreign key to a table.  The name of the foreign key is
   * automatically generated unless Name() is given as an option.
   *
   * @param references the table and column name(s) that the foreign
   *        key references
   * @param on the table and column name(s) to place the foreign key
   *        on
   * @param options a possibly empty list of foreign key options to
   *        customize the creation of the foreign key
   */
  def addForeignKey(references : References,
                    on : On,
                    options : ForeignKeyOption*) : Unit =
  {
    addForeignKey(on, references, options : _*)
  }

  /**
   * Remove a foreign key from a table.  The name of the foreign key
   * is automatically generated unless Name() is given as an option.
   *
   * @param on the table and column name(s) to remove the foreign key
   *        from
   * @param references the table and column name(s) that the foreign
   *        key references
   * @param options a possibly empty list of foreign key options to
   *        customize the removal of the foreign key
   */
  def removeForeignKey(on : On,
                       references : References,
                       options : Name*) : Unit =
  {
    if (on.columnNames.length == 0) {
      throw new IllegalArgumentException("Removing a foreign key constraint " +
                                         "requires at least one column name " +
                                         "in the table adding the constraint.")
    }

    if (references.columnNames.length == 0) {
      throw new IllegalArgumentException("Removing a foreign key constraint " +
                                         "requires at least one column name " +
                                         "from the table being referenced.")
    }

    var (name, opts) = foreignKeyNameFor(on, references, options : _*)

    execute("ALTER TABLE " +
            adapter_.quoteTableName(on.tableName) +
            " DROP CONSTRAINT " +
            name)
  }

  /**
   * Remove a foreign key from a table.  The name of the foreign key
   * is automatically generated unless Name() is given as an option.
   *
   * @param references the table and column name(s) that the foreign
   *        key references
   * @param on the table and column name(s) to remove the foreign key
   *        from
   * @param options a possibly empty list of foreign key options to
   *        customize the removal of the foreign key
   */
  def removeForeignKey(references : References,
                       on : On,
                       options : Name*) : Unit =
  {
    removeForeignKey(on, references, options : _*)
  }

  /**
   * Add a grant on a table to one or more grantees.
   *
   * @param table_name the table name to add the grantees to
   * @param grantees a non-empty array of grantees
   * @param privileges a non-empty array of privileges to grant to the
   *        grantees
   */
  final
  def grant(table_name : String,
            grantees : Array[String],
            privileges : GrantPrivilegeType*) : Unit =
  {
    if (grantees.isEmpty) {
      throw new IllegalArgumentException("Granting permissions requires " +
                                         "at least one grantee.")
    }

    if (privileges.isEmpty) {
      throw new IllegalArgumentException("Granting permissions requires " +
                                         "at least one privilege.")
    }

    val sql = adapter_.grantSql(table_name, grantees, privileges : _*)

    execute(sql)
  }

  /**
   * Add a grant on a table to a grantee.
   *
   * @param table_name the table name to add the grantees to
   * @param grantee the grantee to grant the privileges to
   * @param privileges a non-empty array of privileges to grant to the
   *        grantees
   */
  final
  def grant(table_name : String,
            grantee : String,
            privileges : GrantPrivilegeType*) : Unit =
  {
    grant(table_name, Array(grantee), privileges : _*)
  }

  /**
   * Remove privileges on a table from one or more grantees.
   *
   * @param table_name the table name to remove the grants from
   * @param grantees a non-empty array of grantees
   * @param privileges a non-empty array of privileges to remove from
   *        the grantees
   */
  final
  def revoke(table_name : String,
             grantees : Array[String],
             privileges : GrantPrivilegeType*) : Unit =
  {
    if (grantees.isEmpty) {
      throw new IllegalArgumentException("Revoking permissions requires " +
                                         "at least one grantee.")
    }

    if (privileges.isEmpty) {
      throw new IllegalArgumentException("Revoking permissions requires " +
                                         "at least one privilege.")
    }

    val sql = adapter_.revokeSql(table_name, grantees, privileges : _*)

    execute(sql)
  }

  /**
   * Remove privileges on a table from one or more grantees.
   *
   * @param table_name the table name to remove the grants from
   * @param grantees a non-empty array of grantees
   * @param privileges a non-empty array of privileges to remove from
   *        the grantees
   */
  final
  def revoke(table_name : String,
             grantee : String,
             privileges : GrantPrivilegeType*) : Unit =
  {
    revoke(table_name, Array(grantee), privileges : _*)
  }

  /**
   * Add a CHECK constraint on a table and one or more columns.  The
   * constraint name is automatically generated unless Name() is given
   * as an option.
   *
   * @param on the table and columns to add the CHECK constraint on
   * @param expr the expression to check
   * @param options a possibly empty list of check options to
   *        customize the creation of the CHECK constraint
   */
  def addCheck(on : On,
               expr : String,
               options : CheckOption*) : Unit =
  {
    if (on.columnNames.isEmpty) {
      throw new IllegalArgumentException("Adding a check constraint " +
                                         "requires at least one column name " +
                                         "in the table adding the constraint.")
    }

    var (name, opts) = adapter_.generateCheckConstraintName(on, options : _*)

    val quoted_on_column_names = on.columnNames.map {
                                   adapter_.quoteColumnName(_)
                                 }.mkString(", ")

    val sql = new java.lang.StringBuilder(512)
               .append("ALTER TABLE ")
               .append(adapter_.quoteTableName(on.tableName))
               .append(" ADD CONSTRAINT ")
               .append(name)
               .append(" CHECK (")
               .append(expr)
               .append(")")

    execute(sql.toString)
  }

  /**
   * Remove a CHECK constraint on a table and one or more columns.
   * The constraint name is automatically generated unless Name() is
   * given as an option.
   *
   * @param on the table and columns to remove the CHECK constraint
   *        from
   * @param options a possibly empty list of check options to
   *        customize the removal of the CHECK constraint
   */
  def removeCheck(on : On,
                  options : Name*) : Unit =
  {
    if (on.columnNames.isEmpty) {
      throw new IllegalArgumentException("Removing a check constraint " +
                                         "requires at least one column name " +
                                         "in the table adding the constraint.")
    }

    var (name, opts) = adapter_.generateCheckConstraintName(on, options : _*)

    execute("ALTER TABLE " +
            adapter_.quoteTableName(on.tableName) +
            " DROP CONSTRAINT " +
            name)
  }
}
