# spark_mysql
SparkSQL DataFrame与MySQL增加，删除，修改和查询那些事儿


##### 1.Create（增加）

```java 
  case class CardMember（m_id：String，card_type：String，expire：Timestamp，duration：Int，is_sale：Boolean，date：Date，user：long，salary：Float）

   val memberSeq = Seq（
      CardMember（“member_2”，“月卡”，新时间戳（System.currentTimeMillis（）），31，false，新日期（System.currentTimeMillis（）），123223,0.32f），
      CardMember（“member_1” “，”季卡“，新的时间戳（System.currentTimeMillis（）），93，false，new Date（System.currentTimeMillis（）），124224,0.362f）
    ）
    val memberDF = memberSeq.toDF（）
    //把DataFrame存入到MySQL的中，如果数据库中不存在此表的话就会自动创建
    MySQLUtils.saveDFtoDBCreateTableIfNotExist（ “member_test”，memberDF）

```

##### 2.Retrieve（读取查询）

```java 
     //根据表名把MySQL中的数据表直接映射成DataFrame 
    MySQLUtils.getDFFromMysql（hiveContext，“member_test”，null）
```

##### 3.Update（更新）

```java 
    //根据主键更新指定字段，如果没有此主键数据则直接插入
    MySQLUtils.insertOrUpdateDFtoDBUsePool（“member_test”，memberDF，Array（“user”，“salary”））
```

##### 4.Delete（删除）

```java 
 //删除指定条件的数据
 MySQLUtils.deleteMysqlTable（hiveContext，“member_test”，“m_id ='member_1'”）; 
 //删除指定数据表
 MySQLUtils.dropMysqlTable（hiveContext, “member_test”）; 
```
