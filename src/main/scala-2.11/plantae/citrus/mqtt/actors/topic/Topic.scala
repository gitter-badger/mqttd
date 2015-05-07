package plantae.citrus.mqtt.actors.topic

import akka.actor._

import scala.collection.mutable
import scala.collection.mutable.Map
import scala.util.Random

sealed trait TopicRequest

sealed trait TopicResponse

case class Subscribe(session: ActorRef, qos: Short) extends TopicRequest

case class Unsubscribe(session: ActorRef) extends TopicRequest

case class Subscribed(topicName: String) extends TopicResponse

case class Unsubscribed(topicName: String) extends TopicResponse

case object ClearList extends TopicRequest

case class TopicInMessage(payload: Array[Byte], qos: Short, retain: Boolean, packetId: Option[Int]) extends TopicRequest

case object TopicInMessageAck extends TopicResponse

case class TopicOutMessage(payload: Array[Byte], qos: Short, retain: Boolean, topic: String) extends TopicResponse

case object TopicOutMessageAck extends TopicRequest

case class TopicCreateRequest(topicName: String)

case class TopicCreateResponse(topicName: String, topic: List[ActorRef])

case class TopicExistRequest(topicName: String)

case class TopicExistResponse(topicName: String, topic: Option[List[ActorRef]])


class TopicRoot extends Actor with ActorLogging {

  val root = DiskTreeNode[ActorRef]("", "", Map[String, DiskTreeNode[ActorRef]]())

  override def receive = {
    case topicName: String => {
      root.getNodes(topicName) match {
        case Nil => {
          log.debug("new topic is created[{}]", topicName)
          val topic = context.actorOf(Props(classOf[Topic], topicName), Random.alphanumeric.take(128).mkString)
          root.addNode(topicName, topic)
          sender ! List(topic)
        }
        case topics: List[ActorRef] => sender ! topics
      }
    }
    case TopicCreateRequest(topicName) =>
      root.getNodes(topicName) match {
        case Nil =>
          val topic = context.actorOf(Props(classOf[Topic], topicName), Random.alphanumeric.take(128).mkString)
          root.addNode(topicName, topic)
          sender ! TopicCreateResponse(topicName, List(topic))
        case topics: List[ActorRef] => sender ! TopicCreateResponse(topicName, topics)
      }


    case TopicExistRequest(topicName) =>
      sender ! TopicExistResponse(topicName, root.getNodes(topicName) match {
        case Nil => None
        case topics: List[ActorRef] => Some(topics)
      })
  }
}

class Topic(name: String) extends Actor with ActorLogging {
  private val subscriberMap: mutable.HashSet[ActorRef] = mutable.HashSet()

  def receive = {
    case Subscribe(session) => {
      log.debug("Subscribe client({}) topic({})", session.path.name, name)
      if (!subscriberMap.contains(session))
        subscriberMap.+= (session)

      sender ! Subscribed(name)
      printEverySubscriber
    }

    case Unsubscribe(session) => {
      log.debug("Unsubscribe client({}) topic({})", session.path.name, name)

      subscriberMap.-=(session)
      
      sender ! Unsubscribed(name)
      printEverySubscriber
    }

    case ClearList => {
      log.debug("Clear subscriber list")
      subscriberMap.clear()
      printEverySubscriber
    }

    case TopicInMessage(payload, qos, retain, packetId) => {
      log.debug("qos : {} , retain : {} , payload : {} , sender {}", qos, retain, new String(payload), sender)
      sender ! TopicInMessageAck
      val topicOutMessage = TopicOutMessage(payload, qos, retain, name)
      subscriberMap.par.foreach(
        (actor) => {
          actor ! topicOutMessage
        }
      )
    }
  }

  def printEverySubscriber = {
    log.debug("{}'s subscriber ", name)
    subscriberMap.foreach(s => log.debug("{},", s))
  }
}

case class DiskTreeNode[A](name: String, fullPath: String, children: Map[String, DiskTreeNode[A]] = Map[String, DiskTreeNode[A]]()) {
  var topic: Option[A] = None

  def pathToList(path: String): List[String] = {
    path.split("/").toList
  }

  def addNode(path: String, topic: A): Boolean = {
    addNode(pathToList(path), path, topic)
  }

  def addNode(paths: List[String], path: String, topic: A): Boolean = {
    paths match {
      case Nil => this.topic = Some(topic)
      case _ => {
        children.get(paths.head) match {
          case Some(node: DiskTreeNode[A]) => {
            node.addNode(paths.tail, path, topic)
          }
          case None => {
            val node = new DiskTreeNode[A](paths.head, fullPath + "/" + paths.head)
            node.addNode(paths.tail, path, topic)
            children.+=((paths.head, node))
          }
        }
      }
    }

    true
  }

  def removeNode(path: String): Boolean = {
    removeNode(pathToList(path))
  }

  def removeNode(paths: List[String]): Boolean = {
    if (paths.size == 1) {
      children.-(paths.head)
    } else if (paths.size > 1) {
      children.get(paths.head) match {
        case Some(node: DiskTreeNode[A]) => {
          node.removeNode(paths.tail)
        }
        case None =>
      }
    }

    true
  }

  def getNodes(path: String): List[A] = {
    getNodes(pathToList(path))
  }

  def getNodes(paths: List[String]): List[A] = {
    paths match {
      case Nil => List()
      case x :: Nil => {
        x match {
          case "*" => getEveryNodes()
          case "+" => {
            children.filter(x => x._2.topic.isDefined).map(y => y._2.topic.get).toList
          }
          case _ => {
            children.get(x) match {
              case Some(node: DiskTreeNode[A]) => {
                node.topic match {
                  case Some(t) => List(t)
                  case None => List()
                }

              }
              case None => List()
            }
          }
        }
      }
      case x :: others => {
        x match {
          case "+" => {
            children.map(x => {
              x._2.getNodes(others)
            }).flatten.toList
          }
          case _ => {
            children.get(x) match {
              case Some(node: DiskTreeNode[A]) => node.getNodes(others)
              case None => List()
            }
          }
        }
      }
    }
  }

  def getEveryNodes(): List[A] = {
    val topics = children.map(x => {
      x._2.getEveryNodes()
    }).flatten.toList

    topic match {
      case Some(x) => x :: topics
      case None => topics
    }
  }
}
