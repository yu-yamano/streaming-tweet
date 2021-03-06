package org.littlewings.spark

//import org.apache.lucene.analysis.ja.JapaneseAnalyzer
import org.apache.spark.rdd.JdbcRDD
import org.codelibs.neologd.ipadic.lucene.analysis.ja.JapaneseAnalyzer
import java.io.StringReader
import org.codelibs.neologd.ipadic.lucene.analysis.ja.JapaneseTokenizer
import org.codelibs.neologd.ipadic.lucene.analysis.ja.tokenattributes.BaseFormAttribute
import org.codelibs.neologd.ipadic.lucene.analysis.ja.tokenattributes.PartOfSpeechAttribute
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.spark.SparkContext._
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.rdd.RDD._
import org.apache.spark.streaming.twitter.TwitterUtils
import org.apache.spark.streaming.{Durations, StreamingContext}
import java.util.Properties
import java.sql.{Connection, DriverManager, ResultSet}

object TwitterStreaming {

  def main(args: Array[String]): Unit = {

    println("\n====================== Start. ======================")
    // Set Twitter Access Keys
    //    logger.debug(s"Some message!")
    val config = new java.util.Properties
    // todo: ipかなにかで  devとprod用で設定ファイルを切り替える
    config.load(this.getClass().getClassLoader().getResourceAsStream("config.properties"))
    System.setProperty("twitter4j.oauth.consumerKey", config.get("oauth.consumerKey").toString)
    System.setProperty("twitter4j.oauth.consumerSecret", config.get("oauth.consumerSecret").toString)
    System.setProperty("twitter4j.oauth.accessToken", config.get("oauth.accessToken").toString)
    System.setProperty("twitter4j.oauth.accessTokenSecret", config.get("oauth.accessTokenSecret").toString)

    println(config.get("oauth.accessTokenSecret").toString)
    //    val db = TwitterStreaming.dbconnection(config.get("db.user").toString, config.get("db.password").toString)

    //sparkconf 設定
    val conf = new SparkConf().setAppName("Twitter Streaming")
    val minuteunit: Long = if (args(0).isEmpty) 5 else args(0).toLong
    val ssc = new StreamingContext(conf, Durations.minutes(minuteunit))
    val filter = if (args(1).isEmpty) Nil else Array(args(1)).toList
    val stream = TwitterUtils.createStream(ssc, None, filter)

    // test用コードのためコメントアウト //
//    val tokenizer = new JapaneseTokenizer(null, true, JapaneseTokenizer.DEFAULT_MODE)
//    val text = ”これはテスト用の文言をいれるとこです。"
//    tokenizer.setReader(new StringReader(text))
//    val baseForm = tokenizer.addAttribute(classOf[BaseFormAttribute])
//    val partOfSpeech = tokenizer.addAttribute(classOf[PartOfSpeechAttribute])
//    val charTerm = tokenizer.addAttribute(classOf[CharTermAttribute])
//
//    tokenizer.reset()
//    while (tokenizer.incrementToken()) {
//      println(charTerm.toString + '\t' + baseForm.getBaseForm() + '\t' + partOfSpeech.getPartOfSpeech().split("-")(0) )
//    }
//    System.exit(0)
    /////////////////////////////////

    val tweetRDD = stream
      .flatMap { status =>
        val text = status.getText.replaceAll("http(s*)://(.*)/", "").replaceAll("¥¥uff57", "").replaceAll(args(1).toString, "")
        val analyzer = new JapaneseAnalyzer
        val tokenStream = analyzer.tokenStream("", text)
        val baseForm = tokenStream.addAttribute(classOf[BaseFormAttribute])
        val partOfSpeech = tokenStream.addAttribute(classOf[PartOfSpeechAttribute])
        val charAttr = tokenStream.addAttribute(classOf[CharTermAttribute])

        //resetメソッドを呼んだ後に、incrementTokenメソッドでTokenを読み進めていく
        tokenStream.reset()

        try {
          Iterator
            .continually(tokenStream.incrementToken())
            .takeWhile(identity)
            // 品詞が名詞のものだけ抽出する
            .map(_ => PartOfSpeechCheckConvert(partOfSpeech.getPartOfSpeech(), charAttr.toString))
            .toVector
        } finally {
          tokenStream.end()
        }
      }

    // ２桁以上の文字を対象にアルファベット、数値のみはngwordという単語とする
    val wordAndOnePairRDD = tweetRDD.map(word => (ngwordConvert(word)))
    // (Apache, 1) (Spark, 1) というペアにします。ngwordはゴミ単語なので0を設定
    val ngwordRDD = wordAndOnePairRDD.map(word => (word, word match {
      case ("ngword") => 0
      case _ => 1
    }))

    // countup reduceByKey(_ + _) は　reduceByKey((x, y) => x + y) と等価です。
    val wordAndCountRDD = ngwordRDD.reduceByKey((a, b) => a + b)
    //    val wordAndCountRDD = wordAndOnePairRDD.reduceByKey(_ + _)

    // key => value value => keyに変更
    val countAndWordRDD = wordAndCountRDD.map { wordAndWount => (wordAndWount._2, wordAndWount._1) }

    // sort transformをかまさないとsortByKeyが使えない
    val sortedCWRDD = countAndWordRDD.transform(rdd => rdd.sortByKey(false))

    // value => key key => valueに変更
    val sortedCountAndWordRDD = sortedCWRDD.map { countAndWord => (countAndWord._2, countAndWord._1) }

    // データ保存先をconfigから取得してdevとliveで保存先切り替える
    sortedCountAndWordRDD.saveAsTextFiles(config.get("save.file.dir").toString + args(1))
//    sortedCountAndWordRDD.print()

    // streaming start
    ssc.start()
    ssc.awaitTermination()
  }

  def PartOfSpeechCheckConvert(part: String, word: String) = {
    if (part.split("-")(0) == "名詞") word else "ngword"
  }

  def ngwordConvert(word: String) = {
    if (word.length >= 2) word.replaceAll("(^[a-z]+$)", "ngword").replaceAll("^[0-9]+$", "ngword") else "ngword"
  }

  //    def dbconnection(dbuser: String, dbpassword: String) = {
  //      val dbDriver = "com.mysql.jdbc.Driver"
  //      val dbUrl = "jdbc:mysql://localhost:3306/jdbcrdd?useUnicode=true&characterEncoding=UTF-8"
  //      val dbUsername = dbuser
  //      val dbPassword = dbpassword
  //
  //      val jdbcConnection = () => {
  //        Class.forName(dbDriver).newInstance
  //        DriverManager.getConnection(dbUrl, dbUsername, dbPassword)
  //      }
  //
  //      val mysqlRDD = new JdbcRDD(
  //        context,
  //        jdbcConnection,
  //        "select * from trend"
  //        28,
  //        842105,
  //        10,
  //        r => r.getLong("id") + ", " + r.getString("token")
  //      )
  //
  //      val results = mysqlRDD.collect.toList
  //      println(s"results size = ${results.size}")
  //      System.exit(0)
  //      jdbcConnection
  //
  //    }
}
