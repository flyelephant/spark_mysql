package utils

import java.util.Properties
/**
  * Created with IntelliJ IDEA.
  * Author: fly_elephant@163.com
  * Description:PropertyUtils工具类
  * Date: Created in 2018-11-17 11:43
  */
object PropertyUtils {
  def getFileProperties(fileName: String, propertyKey: String): String = {
    val result = this.getClass.getClassLoader.getResourceAsStream(fileName)
    val prop = new Properties
    prop.load(result)
    prop.getProperty(propertyKey)
  }
}