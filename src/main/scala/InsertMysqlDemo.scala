import java.sql.{Date, Timestamp}

import InsertMysqlDemo.CardMember
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}
import utils.MySQLUtils

/**
  * Created with IntelliJ IDEA.
  * Author: fly_elephant@163.com
  * Description:DataFrame 中数据存入到MySQL
  * Date: Created in 2018-11-17 12:39
  */
object InsertMysqlDemo {

  case class CardMember(m_id: String, card_type: String, expire: Timestamp, duration: Int, is_sale: Boolean, date: Date, user: Long, salary: Float)

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setMaster("local[*]").setAppName(getClass.getSimpleName).set("spark.testing.memory", "3147480000")
    val sparkContext = new SparkContext(conf)
    val hiveContext = new SQLContext(sparkContext)
    import hiveContext.implicits._
    val memberSeq = Seq(
      CardMember("member_2", "月卡", new Timestamp(System.currentTimeMillis()), 31, false, new Date(System.currentTimeMillis()), 123223, 0.32f),
      CardMember("member_1", "季卡", new Timestamp(System.currentTimeMillis()), 93, false, new Date(System.currentTimeMillis()), 124224, 0.362f)
    )
    val memberDF = memberSeq.toDF()
    MySQLUtils.saveDFtoDBCreateTableIfNotExist("member_test", memberDF)
    MySQLUtils.insertOrUpdateDFtoDBUsePool("member_test", memberDF, Array("user", "salary"))
    MySQLUtils.getDFFromMysql(hiveContext, "", null)


    sparkContext.stop()
  }
}
