package com.kent.workflow

import com.kent.workflow.WorkflowInfo.WStatus._
import com.kent.pub.DeepCloneable
import com.kent.workflow.node.NodeInstance
import java.util.Date
import com.kent.workflow.node.NodeInstance
import com.kent.workflow.controlnode.StartNodeInstance
import com.kent.util.Util
import com.kent.pub.Daoable
import java.sql.Connection
import java.sql.ResultSet
import org.json4s.jackson.JsonMethods
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import java.sql.SQLException
import com.kent.workflow.WorkflowInfo.WStatus
import akka.actor.ActorRef
import com.kent.db.PersistManager.Save
import com.kent.db.PersistManager

class WorkflowInstance(val workflow: WorkflowInfo) extends DeepCloneable[WorkflowInstance] with Daoable[WorkflowInstance] {
  var id: String = Util.produce8UUID
  def actorName = s"wf_${id}_${workflow.name}"
  var parsedParams:Map[String, String] = Map()
  var startTime: Date = _
  var endTime: Date = _
  var status: WStatus = W_PREP     
  var nodeInstanceList:List[NodeInstance] = List()
  
  def deepClone(): WorkflowInstance = {
    val wfi = WorkflowInstance(workflow)
    this.deepCloneAssist(wfi)
    wfi
  }
  def deepCloneAssist(wfi: WorkflowInstance): WorkflowInstance = {
    wfi.parsedParams = this.parsedParams.map(x => (x._1,x._2)).toMap
    wfi.startTime = startTime
    wfi.endTime = endTime
    wfi.status = status
    wfi.id = id
    wfi
  }
  override def toString: String = {
    var str = this.getClass().getName + "(\n"
    str = str + s"  id = ${id},\n"
    str = str + s"  name = ${workflow.name},\n"
    str = str + s"  actorName = ${actorName},\n"
    str = str + s"  status = ${status},\n"
    str = str + s"  startTime = ${startTime},\n"
    str = str + s"  endTime = ${endTime},\n"
    this.nodeInstanceList.foreach { x => str = str + x.toString() }
    str = str + s")"
    str
  }
   /**
   * 得到工作流的开始节点
   */
  def getStartNode():Option[NodeInstance] = {
	  val sn = nodeInstanceList.filter { _.isInstanceOf[StartNodeInstance] }.toList
	  if(sn.size == 1) Some(sn(0)) else None
  }

  
  
  
  
  
  def save(implicit conn: Connection): Boolean = {
    var result = false;
    import org.json4s.JsonDSL._
    import org.json4s.jackson.JsonMethods._
    import com.kent.util.Util._
    val paramStr = compact(render(parsedParams))
    try{
      conn.setAutoCommit(false)
  	  val insertSql = s"""
  	     insert into workflow_instance values(${withQuate(id)},${withQuate(workflow.id)},${withQuate(workflow.name)},
  	                                          ${withQuate(paramStr)},'${status.id}',${withQuate(workflow.desc)},
  	                                          ${withQuate(formatStandarTime(startTime))},${withQuate(formatStandarTime(endTime))},
  	                                          ${withQuate(formatStandarTime(workflow.createTime))},${withQuate(formatStandarTime(workflow.createTime))})
  	    """
  	  val updateSql = s"""
  	    update workflow_instance set workflow_id = ${withQuate(workflow.id)}, 
  	                        name = ${withQuate(workflow.name)}, param = ${withQuate(paramStr)}, 
  	                        status = '${status.id}',
  	                        description = ${withQuate(workflow.desc)},
  	                        stime = ${withQuate(formatStandarTime(startTime))}, 
  	                        etime = ${withQuate(formatStandarTime(endTime))},
  	                        create_time = ${withQuate(formatStandarTime(workflow.createTime))}
  	    where id = '${id}'
  	    """
  	  if(this.getEntity.isEmpty){
      	result = executeSql(insertSql)     
  			executeSql(s"delete from node_instance where workflow_instance_id='${id}'")
  			this.nodeInstanceList.foreach { _.save }
      }else{
        result = executeSql(updateSql)
      }
  	  conn.commit()
    }catch{
      case e: SQLException => e.printStackTrace(); conn.rollback()
    }
	  result
  }

  def getEntity(implicit conn: Connection): Option[WorkflowInstance] = {
    getEntityWithNodeInstance(conn, true)
  }
  
  def getEntityWithNodeInstance(implicit conn: Connection, isWithNodeInstance: Boolean): Option[WorkflowInstance] = {
    import com.kent.util.Util._
    val wfi = this.deepClone()
    //工作流实例查询sql
    val queryStr = s"""
         select id,workflow_id,name,param,status,description,stime,etime,create_time,last_update_time
         from workflow_instance where id=${withQuate(id)}
                    """
    println(queryStr)
    //节点实例查询sql
    val queryNodesStr = s"""
         select name,type from node_instance 
         where workflow_instance_id = ${withQuate(id)}
      """
    val wfiOpt = querySql(queryStr, (rs: ResultSet) =>{
          if(rs.next()){
            wfi.id = rs.getString("id")
            wfi.workflow.desc = rs.getString("description")
            wfi.workflow.name = rs.getString("name")
            wfi.workflow.id = rs.getString("workflow_id")
            wfi.status = WStatus.getWstatusWithId(rs.getInt("status"))
            wfi.workflow.createTime = Util.getStandarTimeWithStr(rs.getString("create_time"))
            wfi.startTime = Util.getStandarTimeWithStr(rs.getString("stime"))
            wfi.endTime = Util.getStandarTimeWithStr(rs.getString("etime"))
            val json = JsonMethods.parse(rs.getString("param"))
            val list = for{ JObject(ele) <- json; (k, JString(v)) <- ele} yield (k->v)
            wfi.parsedParams = list.map(x => x).toMap
            wfi
          }else{
            null
          }
      })
    //关联查询节点实例
    if(isWithNodeInstance && !wfiOpt.isEmpty){
      querySql(queryNodesStr, (rs: ResultSet) => {
        var nameTypes = List[(String, String)]()
        while(rs.next()) nameTypes =  (rs.getString("name"), rs.getString("type")) :: nameTypes
        val nis = nameTypes.map { x => NodeInstance(x._2, x._1, id).getEntity.get }.toList
        wfi.nodeInstanceList = nis
        wfi
      })
    }else{
      wfiOpt
    }
  }

  def delete(implicit conn: Connection): Boolean = {
    var result = false
    try{
      conn.setAutoCommit(false)
	    result = executeSql(s"delete from workflow_instance where id='${id}'")
	    executeSql(s"delete from node_instance where workflow_instance_id='${id}'")
	    conn.commit()
    }catch{
      case e: SQLException => e.printStackTrace();conn.rollback()
    }
    result
  }
}

object WorkflowInstance {
  /**
   * 由一个workflow对象产生一个实例
   */
  def apply(wf: WorkflowInfo): WorkflowInstance = {
    if(wf != null){
    	val wfi = new WorkflowInstance(wf.deepClone())
    	wfi.nodeInstanceList = wfi.workflow.nodeList.map { _.createInstance(wfi.id) }.toList
    	wfi      
    } else{
      val wfi = new WorkflowInstance(null)
      wfi
    }
  }
}