package algorithm.clustering.lda

import algorithm.utils.LDAUtils
import org.apache.log4j.{Level, Logger}
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}


object LDATrainDemo {

  def main(args: Array[String]) {
    Logger.getRootLogger.setLevel(Level.WARN)

    val conf = new SparkConf().setAppName("LDATrain").setMaster("local")
    val sc = new SparkContext(conf)

    val ldaUtils = LDAUtils("config/lda.properties")

    val args = Array("data/preprocess_result.txt", "G:/test/LDAModel")

    val inFile = args(0)
    val outFile = args(1)

    val textRDD = sc.textFile(inFile).filter(_.nonEmpty).map(_.split("\\|")).map(line => (line(0).toLong, line(1)))
    val (ldaModel, vocabulary, documents, tokens) = ldaUtils.train(sc, textRDD)

    val docTopics: RDD[(Long, Vector)] = ldaUtils.calcDocTopics(ldaModel, documents)

    println("文档-主题分布：")
    docTopics.collect().foreach(doc => {
      println(doc._1 + ": " + doc._2)
    })

    val topicWords: Array[Array[(String, Double)]] = ldaUtils.calcTopicWords(ldaModel, vocabulary.collect())
    println("主题-词：")
    topicWords.zipWithIndex.foreach(topic => {
      println("Topic: " + topic._2)
      topic._1.foreach(word => {
        println(word._1 + "\t" + word._2)
      })
      println()
    })

    ldaUtils.saveModel(sc, outFile, ldaModel, tokens)

    sc.stop()
  }
}