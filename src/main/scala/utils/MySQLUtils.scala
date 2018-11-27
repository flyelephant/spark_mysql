package utils

import java.sql.{Date, Timestamp}
import java.util.Properties

import org.apache.log4j.Logger
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SQLContext}
/**
  * Created with IntelliJ IDEA.
  * Author: fly_elephant@163.com
  * Description:MySQL DDL 和DML 工具类
  * Date: Created in 2018-11-17 12:43
  */
object MySQLUtils {
  val logger: Logger = Logger.getLogger(getClass.getSimpleName)

  /**
    * 将DataFrame所有类型(除id外)转换为String后,通过c3p0的连接池方法，向mysql写入数据
    *
    * @param tableName       表名
    * @param resultDateFrame DataFrame
    */
  def saveDFtoDBUsePool(tableName: String, resultDateFrame: DataFrame) {
    val colNumbers = resultDateFrame.columns.length
    val sql = getInsertSql(tableName, colNumbers)
    val columnDataTypes = resultDateFrame.schema.fields.map(_.dataType)
    resultDateFrame.foreachPartition(partitionRecords => {
      val conn = MySQLPoolManager.getMysqlManager.getConnection //从连接池中获取一个连接
      val preparedStatement = conn.prepareStatement(sql)
      val metaData = conn.getMetaData.getColumns(null, "%", tableName, "%") //通过连接获取表名对应数据表的元数据
      try {
        conn.setAutoCommit(false)
        partitionRecords.foreach(record => {
          //注意:setString方法从1开始，record.getString()方法从0开始
          for (i <- 1 to colNumbers) {
            val value = record.get(i - 1)
            val dateType = columnDataTypes(i - 1)
            if (value != null) { //如何值不为空,将类型转换为String
              preparedStatement.setString(i, value.toString)
              dateType match {
                case _: ByteType => preparedStatement.setInt(i, record.getAs[Int](i - 1))
                case _: ShortType => preparedStatement.setInt(i, record.getAs[Int](i - 1))
                case _: IntegerType => preparedStatement.setInt(i, record.getAs[Int](i - 1))
                case _: LongType => preparedStatement.setLong(i, record.getAs[Long](i - 1))
                case _: BooleanType => preparedStatement.setBoolean(i, record.getAs[Boolean](i - 1))
                case _: FloatType => preparedStatement.setFloat(i, record.getAs[Float](i - 1))
                case _: DoubleType => preparedStatement.setDouble(i, record.getAs[Double](i - 1))
                case _: StringType => preparedStatement.setString(i, record.getAs[String](i - 1))
                case _: TimestampType => preparedStatement.setTimestamp(i, record.getAs[Timestamp](i - 1))
                case _: DateType => preparedStatement.setDate(i, record.getAs[Date](i - 1))
                case _ => throw new RuntimeException(s"nonsupport ${dateType} !!!")
              }
            } else { //如果值为空,将值设为对应类型的空值
              metaData.absolute(i)
              preparedStatement.setNull(i, metaData.getInt("DATA_TYPE"))
            }
          }
          preparedStatement.addBatch()
        })
        preparedStatement.executeBatch()
        conn.commit()
      } catch {
        case e: Exception => println(s"@@ saveDFtoDBUsePool ${e.getMessage}")
        // do some log
      } finally {
        preparedStatement.close()
        conn.close()
      }
    })
  }

  /**
    * 拼装insert SQL
    * @param tableName
    * @param colNumbers
    * @return
    */
  def getInsertSql(tableName: String, colNumbers: Int): String = {
    var sqlStr = "insert into " + tableName + " values("
    for (i <- 1 to colNumbers) {
      sqlStr += "?"
      if (i != colNumbers) {
        sqlStr += ", "
      }
    }
    sqlStr += ")"
    sqlStr
  }

  /** 以元组的方式返回mysql属性信息 **/
  def getMySQLInfo: (String, String, String) = {
    val jdbcURL = PropertyUtils.getFileProperties("mysql-user.properties", "mysql.jdbc.url")
    val userName = PropertyUtils.getFileProperties("mysql-user.properties", "mysql.jdbc.username")
    val passWord = PropertyUtils.getFileProperties("mysql-user.properties", "mysql.jdbc.password")
    (jdbcURL, userName, passWord)
  }

  /**
    * 从MySql数据库中获取DateFrame
    *
    * @param sqlContext     sqlContext
    * @param mysqlTableName 表名
    * @param queryCondition 查询条件(可选)
    * @return DateFrame
    */
  def getDFFromMysql(sqlContext: SQLContext, mysqlTableName: String, queryCondition: String): DataFrame = {
    val (jdbcURL, userName, passWord) = getMySQLInfo
    val prop = new Properties()
    prop.put("user", userName)
    prop.put("password", passWord)

    if (null == queryCondition || "" == queryCondition)
      sqlContext.read.jdbc(jdbcURL, mysqlTableName, prop)
    else
      sqlContext.read.jdbc(jdbcURL, mysqlTableName, prop).where(queryCondition)
  }

  /**
    * 删除数据表
    * @param sqlContext
    * @param mysqlTableName
    * @return
    */
  def dropMysqlTable(sqlContext: SQLContext, mysqlTableName: String): Boolean = {
    val conn = MySQLPoolManager.getMysqlManager.getConnection //从连接池中获取一个连接
    val preparedStatement = conn.createStatement()
    try {
      preparedStatement.execute(s"drop table $mysqlTableName")
    } catch {
      case e: Exception =>
        println(s"mysql dropMysqlTable error:${e.getMessage}")
        false
    } finally {
      preparedStatement.close()
      conn.close()
    }
  }

  /**
    *  删除表中的数据
    * @param sqlContext
    * @param mysqlTableName
    * @param condition
    * @return
    */
  def deleteMysqlTableData(sqlContext: SQLContext, mysqlTableName: String, condition: String): Boolean = {
    val conn = MySQLPoolManager.getMysqlManager.getConnection //从连接池中获取一个连接
    val preparedStatement = conn.createStatement()
    try {
      preparedStatement.execute(s"delete from $mysqlTableName where $condition")
    } catch {
      case e: Exception =>
        println(s"mysql deleteMysqlTable error:${e.getMessage}")
        false
    } finally {
      preparedStatement.close()
      conn.close()
    }
  }

  /**
    * 保存DataFrame 到 MySQL中，如果表不存在的话，会自动创建
    * @param tableName
    * @param resultDateFrame
    */
  def saveDFtoDBCreateTableIfNotExist(tableName: String, resultDateFrame: DataFrame) {
    //如果没有表,根据DataFrame建表
    createTableIfNotExist(tableName, resultDateFrame)
    //验证数据表字段和dataFrame字段个数和名称,顺序是否一致
    verifyFieldConsistency(tableName, resultDateFrame)
    //保存df
    saveDFtoDBUsePool(tableName, resultDateFrame)
  }


  /**
    * 拼装insertOrUpdate SQL 语句
    * @param tableName
    * @param cols
    * @param updateColumns
    * @return
    */
  def getInsertOrUpdateSql(tableName: String, cols: Array[String], updateColumns: Array[String]): String = {
    val colNumbers = cols.length
    var sqlStr = "insert into " + tableName + " values("
    for (i <- 1 to colNumbers) {
      sqlStr += "?"
      if (i != colNumbers) {
        sqlStr += ", "
      }
    }
    sqlStr += ") ON DUPLICATE KEY UPDATE "

    updateColumns.foreach(str => {
      sqlStr += s"$str = ?,"
    })

    sqlStr.substring(0, sqlStr.length - 1)
  }

  /**
    * 通过insertOrUpdate的方式把DataFrame写入到MySQL中，注意：此方式，必须对表设置主键
    * @param tableName
    * @param resultDateFrame
    * @param updateColumns
    */
  def insertOrUpdateDFtoDBUsePool(tableName: String, resultDateFrame: DataFrame, updateColumns: Array[String]) {
    val colNumbers = resultDateFrame.columns.length
    val sql = getInsertOrUpdateSql(tableName, resultDateFrame.columns, updateColumns)
    val columnDataTypes = resultDateFrame.schema.fields.map(_.dataType)
    println("############## sql = " + sql)
    resultDateFrame.foreachPartition(partitionRecords => {
      val conn = MySQLPoolManager.getMysqlManager.getConnection //从连接池中获取一个连接
      val preparedStatement = conn.prepareStatement(sql)
      val metaData = conn.getMetaData.getColumns(null, "%", tableName, "%") //通过连接获取表名对应数据表的元数据
      try {
        conn.setAutoCommit(false)
        partitionRecords.foreach(record => {
          //注意:setString方法从1开始，record.getString()方法从0开始
          for (i <- 1 to colNumbers) {
            val value = record.get(i - 1)
            val dateType = columnDataTypes(i - 1)
            if (value != null) { //如何值不为空,将类型转换为String
              preparedStatement.setString(i, value.toString)
              dateType match {
                case _: ByteType => preparedStatement.setInt(i, record.getAs[Int](i - 1))
                case _: ShortType => preparedStatement.setInt(i, record.getAs[Int](i - 1))
                case _: IntegerType => preparedStatement.setInt(i, record.getAs[Int](i - 1))
                case _: LongType => preparedStatement.setLong(i, record.getAs[Long](i - 1))
                case _: BooleanType => preparedStatement.setInt(i, if (record.getAs[Boolean](i - 1)) 1 else 0)
                case _: FloatType => preparedStatement.setFloat(i, record.getAs[Float](i - 1))
                case _: DoubleType => preparedStatement.setDouble(i, record.getAs[Double](i - 1))
                case _: StringType => preparedStatement.setString(i, record.getAs[String](i - 1))
                case _: TimestampType => preparedStatement.setTimestamp(i, record.getAs[Timestamp](i - 1))
                case _: DateType => preparedStatement.setDate(i, record.getAs[Date](i - 1))
                case _ => throw new RuntimeException(s"nonsupport ${dateType} !!!")
              }
            } else { //如果值为空,将值设为对应类型的空值
              metaData.absolute(i)
              preparedStatement.setNull(i, metaData.getInt("DATA_TYPE"))
            }

          }
          //设置需要更新的字段值
          for (i <- 1 to updateColumns.length) {
            val fieldIndex = record.fieldIndex(updateColumns(i - 1))
            val value = record.get(fieldIndex)
            val dataType = columnDataTypes(fieldIndex)
            println(s"@@ $fieldIndex,$value,$dataType")
            if (value != null) { //如何值不为空,将类型转换为String
              dataType match {
                case _: ByteType => preparedStatement.setInt(colNumbers + i, record.getAs[Int](fieldIndex))
                case _: ShortType => preparedStatement.setInt(colNumbers + i, record.getAs[Int](fieldIndex))
                case _: IntegerType => preparedStatement.setInt(colNumbers + i, record.getAs[Int](fieldIndex))
                case _: LongType => preparedStatement.setLong(colNumbers + i, record.getAs[Long](fieldIndex))
                case _: BooleanType => preparedStatement.setBoolean(colNumbers + i, record.getAs[Boolean](fieldIndex))
                case _: FloatType => preparedStatement.setFloat(colNumbers + i, record.getAs[Float](fieldIndex))
                case _: DoubleType => preparedStatement.setDouble(colNumbers + i, record.getAs[Double](fieldIndex))
                case _: StringType => preparedStatement.setString(colNumbers + i, record.getAs[String](fieldIndex))
                case _: TimestampType => preparedStatement.setTimestamp(colNumbers + i, record.getAs[Timestamp](fieldIndex))
                case _: DateType => preparedStatement.setDate(colNumbers + i, record.getAs[Date](fieldIndex))
                case _ => throw new RuntimeException(s"nonsupport ${dataType} !!!")
              }
            } else { //如果值为空,将值设为对应类型的空值
              metaData.absolute(colNumbers+i)
              preparedStatement.setNull(colNumbers+i, metaData.getInt("DATA_TYPE"))
            }
          }
          preparedStatement.addBatch()
        })
        preparedStatement.executeBatch()
        conn.commit()
      } catch {
        case e: Exception => println(s"@@ insertOrUpdateDFtoDBUsePool ${e.getMessage}")
        // do some log
      } finally {
        preparedStatement.close()
        conn.close()
      }
    })
  }


  /**
    * 如果数据表不存在,根据DataFrame的字段创建数据表,数据表字段顺序和dataFrame对应
    * 若DateFrame出现名为id的字段,将其设为数据库主键(int,自增,主键),其他字段会根据DataFrame的DataType类型来自动映射到MySQL中
    *
    * @param tableName 表名
    * @param df        dataFrame
    * @return
    */
  def createTableIfNotExist(tableName: String, df: DataFrame): AnyVal = {
    val con = MySQLPoolManager.getMysqlManager.getConnection
    val metaData = con.getMetaData
    val colResultSet = metaData.getColumns(null, "%", tableName, "%")
    //如果没有该表,创建数据表
    if (!colResultSet.next()) {
      //构建建表字符串
      val sb = new StringBuilder(s"CREATE TABLE `$tableName` (")
      df.schema.fields.foreach(x =>
        if (x.name.equalsIgnoreCase("id")) {
          sb.append(s"`${x.name}` int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,") //如果是字段名为id,设置主键,整形,自增
        } else {
          x.dataType match {
            case _: ByteType => sb.append(s"`${x.name}` int(100) DEFAULT NULL,")
            case _: ShortType => sb.append(s"`${x.name}` int(100) DEFAULT NULL,")
            case _: IntegerType => sb.append(s"`${x.name}` int(100) DEFAULT NULL,")
            case _: LongType => sb.append(s"`${x.name}` bigint(100) DEFAULT NULL,")
            case _: BooleanType => sb.append(s"`${x.name}` tinyint DEFAULT NULL,")
            case _: FloatType => sb.append(s"`${x.name}` float(50) DEFAULT NULL,")
            case _: DoubleType => sb.append(s"`${x.name}` double(50) DEFAULT NULL,")
            case _: StringType => sb.append(s"`${x.name}` varchar(50) DEFAULT NULL,")
            case _: TimestampType => sb.append(s"`${x.name}` timestamp DEFAULT current_timestamp,")
            case _: DateType => sb.append(s"`${x.name}` date  DEFAULT NULL,")
            case _ => throw new RuntimeException(s"nonsupport ${x.dataType} !!!")
          }
        }
      )
      sb.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8")
      val sql_createTable = sb.deleteCharAt(sb.lastIndexOf(',')).toString()
      println(sql_createTable)
      val statement = con.createStatement()
      statement.execute(sql_createTable)
    }
  }

  /**
    * 验证数据表和dataFrame字段个数,名称,顺序是否一致
    *
    * @param tableName 表名
    * @param df        dataFrame
    */
  def verifyFieldConsistency(tableName: String, df: DataFrame): Unit = {
    val con = MySQLPoolManager.getMysqlManager.getConnection
    val metaData = con.getMetaData
    val colResultSet = metaData.getColumns(null, "%", tableName, "%")
    colResultSet.last()
    val tableFiledNum = colResultSet.getRow
    val dfFiledNum = df.columns.length
    if (tableFiledNum != dfFiledNum) {
      throw new Exception(s"数据表和DataFrame字段个数不一致!!table--$tableFiledNum but dataFrame--$dfFiledNum")
    }
    for (i <- 1 to tableFiledNum) {
      colResultSet.absolute(i)
      val tableFileName = colResultSet.getString("COLUMN_NAME")
      val dfFiledName = df.columns.apply(i - 1)
      if (!tableFileName.equals(dfFiledName)) {
        throw new Exception(s"数据表和DataFrame字段名不一致!!table--'$tableFileName' but dataFrame--'$dfFiledName'")
      }
    }
    colResultSet.beforeFirst()
  }

}