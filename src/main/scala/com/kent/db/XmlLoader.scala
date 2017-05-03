package com.kent.db

import akka.actor.Actor
import com.kent.util.FileUtil
import java.io.File
import scala.io.Source
import akka.pattern.{ ask, pipe }
import akka.actor.ActorLogging
import com.kent.coordinate.Coordinator
import akka.actor.ActorRef
import scala.concurrent.ExecutionContext.Implicits.global
import com.kent.workflow.WorkFlowManager.AddWorkFlow
import akka.util.Timeout
import scala.concurrent.duration._
import com.kent.main.HttpServer.ResponseData
import scala.concurrent.Future
import scala.util.Success
import com.kent.coordinate.CoordinatorManager.Start
import com.kent.coordinate.CoordinatorManager.AddCoor
import akka.actor.Cancellable
import com.kent.coordinate.CoordinatorManager.Stop

class XmlLoader(wfXmlPath: String, coorXmlPath: String, interval: Int) extends Actor with ActorLogging{
  var fileMap: Map[String,Long] = Map()
  var scheduler:Cancellable = _;
  implicit val timeout = Timeout(20 seconds)
  
  def receive: Actor.Receive = {
    case Start() => start()
    case Stop() => stop()
  }
  def start():Boolean = {
    this.scheduler = context.system.scheduler.schedule(0 millis, interval seconds){
      this.loadXmlFiles()
    }
    true
  }
  def stop() = {
    if(this.scheduler != null && !this.scheduler.isCancelled) this.scheduler.cancel()
    context.stop(self)
  }
  /**
   * 读取xml文件
   */
  def loadXmlFiles():Boolean = {
    def getNewFileContents(path: String):List[Tuple2[File,String]] = {
    		val files = FileUtil.listFilesWithExtensions(new File(path), List("xml"))
    		//新增或修改
    		val newFiles = files.filter { x => fileMap.get(x.getName).isEmpty || fileMap(x.getName) != x.lastModified()}.toList
    		newFiles.foreach { x => fileMap += (x.getName -> x.lastModified) }
    		newFiles.map { x =>
    				var content = ""
    				Source.fromFile(x).foreach { content += _ }
    				(x,content)
    		}.toList
    }
    val wfXmls = getNewFileContents(wfXmlPath)
    val coorXmls = getNewFileContents(coorXmlPath)
    val wfManager = context.actorSelection("../wfm")
    val cmManager = context.actorSelection("../cm")
    val resultListXmlF = wfXmls.map{ x => (wfManager ? AddWorkFlow(x._2)).mapTo[ResponseData]}.toList
    val resultListCoorF = coorXmls.map{ x => (cmManager ? AddCoor(x._2)).mapTo[ResponseData]}.toList
    val resultWfXmlF = Future.sequence(resultListXmlF)
    val resultCoorXmlF = Future.sequence(resultListCoorF)
    resultWfXmlF.andThen{
      case Success(resultL) => 
        var i = 0
        resultL.foreach { x =>
          if(x.result == "success")log.info(s"[success]解析workflow: ${wfXmls(i)._1}: ${x.msg}") 
          else log.error(s"[error]解析workflow: ${wfXmls(i)._1}: ${x.msg}")
          i += 1
        }
    }
    resultCoorXmlF.andThen{
      case Success(resultL) => 
        var i = 0
        resultL.foreach { x =>
          if(x.result == "success")log.info(s"[success]解析coordinator: ${coorXmls(i)._1}: ${x.msg}") 
          else log.error(s"[error]解析coordinator: ${coorXmls(i)._1}: ${x.msg}")
          i += 1
        }
    }
    
    
    false
  }
}

object XmlLoader{
  def apply(wfXmlPath: String, coorXmlPath: String, interval: Int) = new XmlLoader(wfXmlPath, coorXmlPath, interval)
}