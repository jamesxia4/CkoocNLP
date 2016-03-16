package preprocess.utils

import java.util
import java.util.Properties

import com.hankcs.hanlp.HanLP
import org.ansj.domain.Term
import org.ansj.splitWord.analysis.NlpAnalysis
import org.ansj.util.FilterModifWord
import org.apache.commons.lang3.StringUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.{Logging, SparkConf, SparkContext }
import org.nlpcn.commons.lang.standardization.SentencesUtil
import preprocess.utils.chinese.ZHConverter

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * Created by yhao on 2016/3/12.
  */
class PreProcessUtils (config: Config) extends Logging with Serializable {

  private val enExpr = "[A-Za-z]+".r
  //英文字符正则
  private val numExpr = "\\d+(\\.\\d+)?(\\/\\d+)?".r
  //数值正则，可以匹配203,2.23,2/12
  private val baseExpr = """[^\w-\s+\u4e00-\u9fa5]""".r //匹配英文字母、数字、中文汉字之外的字符

  private val zhConverter = ZHConverter.getInstance(1)

  /**
    * 全角转半角
    */
  private def q2b(line: String): String = {
    zhConverter.convert(line)
  }


  /**
    * 基础清洗: 繁转简体、全角转半角、去除不可见字符
    *
    * @param line 输入数据
    * @return 清洗后的数据
    */
  private def baseClean(line: String): String = {
    var result = line
    if (config.f2j) {
      result = HanLP.convertToSimplifiedChinese(line)
    }
    if (config.q2b) {
      result = q2b(result) //全角转半角
    }
    result = baseExpr.replaceAllIn(result, "")
    StringUtils.trimToEmpty(result)
  }


  /**
    * 针对单行记录
    * 基础清理函数，包括去除不可见字符，数值替换，去英文字符
    *
    * @param delNum 替换数值
    * @param numToChar  数字的替换内容
    * @param delEn 是否去掉英文字符
    */
  def getBaseCleanFunc(delNum: Boolean, numToChar: String = "", delEn: Boolean): (String) => String = {
    (line: String) =>
    {
      var result = line.trim()
      result = baseClean(result)
      if (delNum) {
        result = numExpr.replaceAllIn(result, numToChar)
      }
      if (delEn) {
        result = enExpr.replaceAllIn(result, "")
      }
      result
    }
  }


  /**
    * 分词,每行返回一个Seq[String]的分词结果
    *
    * @param text 原文本
    * @param delStopword 是否去停用词
    * @param minTermNum 分词后分词的个数，只保留大于指定个数的内容
    * @param minTermSize 单个term的长度,只保留大于指定长度的term
    */
  def wordSegment(text: String, delStopword: Boolean, minTermSize: Int, minTermNum: Int, toSentence: Boolean = false): Option[Seq[String]] = {
    var arrayBuffer = ArrayBuffer[String]()
    if (text != null && text != "") {
      val tmp = new util.ArrayList[Term]()
      if (!toSentence) {
        var result = NlpAnalysis.parse(text)
        if (delStopword) {
          result = FilterModifWord.modifResult(result)
        }
        tmp.addAll(result)
      } else {
        val sentences = new SentencesUtil().toSentenceList(text)
        for (i <- 0 until sentences.size()) {
          var result = NlpAnalysis.parse(sentences.get(i))
          if (delStopword) {
            result = FilterModifWord.modifResult(result)
          }
          tmp.addAll(result)
        }
      }
      for (i <- 0 until tmp.size()) {
        val term = tmp.get(i)
        var item = term.getName.trim()
        if (item.length() >= minTermSize) {
          arrayBuffer += item
        }
      }
      if (arrayBuffer.size >= minTermNum) {
        Some(arrayBuffer.toSeq)
      } else {
        None
      }
    } else {
      None
    }
  }


  /**
    * 获取低频词
    * @param rareTermNum  词频阀值，低于此阀值的将会被过滤
    * @param wordRDD  词序列
    * @return 低频词数组
    */
  def getRareTerms(rareTermNum: Int, wordRDD: RDD[Seq[String]]): Array[String] = {
    val wc = wordRDD.flatMap(words => words).map((_, 1)).reduceByKey(_ + _)
    val result = wc.filter(word => word._2 < rareTermNum).map(word => word._1)
    result.collect()
  }


  /**
    * 删除低频词
    * @param words  输入词序列
    * @param rares  低频词数组
    * @return 删除低频词后的词
    */
  def delRareTerms(words: Seq[String], rares: Array[String]): Seq[String] = {
    val result = new ArrayBuffer[String]()
    val wordsArray = words.toArray

    for (word <- wordsArray) {
      if (!rares.contains(word)) {
        result += word
      }
    }

    result.toSeq
  }


  /**
    * 分句，对文本按标点进行分句
    *
    * @param content  一行文本
    * @return 每一句为一个元素的数组
    */
  def getToSentences(content: String, minLength: Int): Array[String] = {
    val result = mutable.ArrayBuffer[String]()
//    val puncs = Array(',', '，', '.', '。', '!', '！', '?', '？', ';', '；', ':', '：', '\'', '‘', '’', '\"', '”', '“', '、', '(', '（', ')', '）', '<', '《', '>', '》', '[', '【', '】', ']', '{', '}', ' ', '\t', '\r', '\n') // 标点集合
    val puncs = Array('.', '。', '!', '！', '?', '？', '\t', '\r', '\n') // 标点集合
    var tmp = ""
    var i = 0
    var before = ' '
    for (ch <- content) {
      i += 1
      if (ch == '.' && Character.isDigit(before) && i < content.length && Character.isDigit(content.charAt(i))) {
        tmp += ch
      }
      else if (puncs.contains(ch)) {
        if (tmp != "") {
          result ++= dealWithSpecialCase(tmp)
          tmp = ""
        }
      }
      else {
        if (isMeaningful(ch)) tmp += ch
        if (i == content.length) {
          result ++= dealWithSpecialCase(tmp)
          tmp = ""
        }
      }
      before = ch
    }

    result.filter(sentence => sentence.length >= minLength).toArray
  }

  /**
    * 分段，对文本按指定的分隔符分段
    *
    * @param content  输入的一行数据
    * @param sep  分隔符
    * @return 每一段为一个元素的数组
    */
  def getToParagraphs(content: String, sep: String): Array[String] = {
    val result = new ArrayBuffer[String]()
    val paragraphs = content.split(sep)
    for (paragraph <- paragraphs) {
      val filterParagraph = paragraph.trim
      if (filterParagraph != null && filterParagraph != "") {
        result += filterParagraph
      }
    }

    result.toArray
  }

  /**
    * 判断符号是否有意义
    *
    * @param ch 输入字符
    * @return 是否有意义，如果是则返回true
    */
  private def isMeaningful(ch: Char): Boolean = {
    var result = false
    val meaningfulMarks = Array('*', '-', 'X', '.','\\')
    if ((ch >= '一' && ch <= '龥') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || meaningfulMarks.contains(ch))
      result = true

    result
  }

  /**
    * 文本中出现明文的\r\n等转以符号
    *
    * @param content  一行文本
    * @return
    */
  private def dealWithSpecialCase(content: String): Array[String] = {
    val patterns = Array("\\\\r\\\\n", "\\\\n", "\\\\t", "[0-9]{8,}")
    val tmp = mutable.ArrayBuffer[String]()
    val result = mutable.ArrayBuffer[String]()
    result += content
    for (pat <- patterns) {
      tmp.clear()
      for (ele <- result) {
        val e = ele.trim()
        if (e != "") {
          tmp ++= e.replaceAll(pat, "|").split( """\|""")
        }
      }
      result.clear()
      result ++= tmp.clone()
    }

    result.toArray
  }



  /**
    * 分词主函数
    * 执行分词，返回一个Seq[String]类型的RDD数据，分词结果不用连接符连接
    *
    * @param sc SparkContext
    * @param rdd 输入的一行数据
    * @return 一个元素代表一条记录
    */
  def runSplitWords(sc: SparkContext, rdd: RDD[String]): RDD[Seq[String]] = {
    val splitWord = config.splitWord
    val delStopword = config.delStopword
    val toSentence = config.toSentence
    val minLineLen = config.minLineLen
    val minTermSize = config.minTermSize
    val minTermNum = config.minTermNum
    val rareTermNum = config.rareTermNum
    val delNum = config.delNum
    val delEn = config.delEn
    val toParagraphs = config.toParagraphs
    val paragraphSeparator = config.paragraphSeparator
    val numToChar = config.numToChar

    var contentRDD = rdd

    //是否分段
    if (toParagraphs) {
      contentRDD = contentRDD.flatMap(line => getToParagraphs(line, paragraphSeparator))
    }

    //是否分句，并且过滤最小句子长度
    if (toSentence) {
      contentRDD = contentRDD.flatMap(line => getToSentences(line, minLineLen))
    } else {
      contentRDD = contentRDD.filter(line => line.length >= minLineLen)
    }

    //清洗数据，分词，去除停用词
    var resultRDD = contentRDD.mapPartitions(str => str.map(getBaseCleanFunc(delNum, numToChar, delEn))).map(line => wordSegment(line, delStopword, minTermSize, minTermNum, toSentence)).filter(_.nonEmpty).map(_.get)

    //去除低频词
    if (config.delRareTerm) {
      val rareArray = getRareTerms(rareTermNum, resultRDD)
      resultRDD = resultRDD.map(words => delRareTerms(words, rareArray))
    }

    resultRDD
  }
}

object PreProcessUtils extends Logging {

  def apply(): PreProcessUtils = {
    PreProcessUtils("config/nlp-config.properties")
  }

  def apply(conf: Config): PreProcessUtils = {
    new PreProcessUtils(conf)
  }

  def apply(confFile: String): PreProcessUtils = {
    val config = Config(confFile)
    new PreProcessUtils(config)
  }

  def apply(prop: Properties): PreProcessUtils = {
    val config = Config(prop)
    new PreProcessUtils(config)
  }


  /**
    * 抽取指定字段的内容
    *
    * @param line 输入文本
    * @param sep  切分符
    * @param fieldNum 要提取的字段，从0开始
    * @return 提取的字段数组
    */
  def contentExtract(line: String, sep: String, fieldNum: Int*): Array[String] = {
    val tokens = line.split(sep)
    val content = new Array[String](fieldNum.length)
    for (i <- fieldNum.indices) {
      content(i) = tokens(fieldNum(i))
    }

    content
  }



  def main(args: Array[String]) {
    val nlpUtils = PreProcessUtils()
    val fileUtils = new FileUtils

    val conf = new SparkConf().setAppName("DataPreProcess").setMaster("local[2]")
    val sc = new SparkContext(conf)

    val inFile = "G:/test/sample.txt"
    val sep = "\u00EF"

    val textRDD = sc.textFile(inFile).map(line => contentExtract(line, sep, 6, 13).mkString(""))

    val splitedRDD = nlpUtils.runSplitWords(sc, textRDD)

    splitedRDD.map(words => words.mkString(" ")).foreach(println)

    sc.stop()
  }
}