import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import scala.concurrent.duration._
import java.security.MessageDigest
import scala.annotation.tailrec
import akka.actor.Terminated

import akka.event.LoggingReceive
import scala.util.control.Breaks
import com.typesafe.config.ConfigFactory
import scala.collection.mutable.ArrayBuffer

case class setParams(zeroes: Int, chunk: Int, limit: Int)
case class isValid(str: String)
case class Mail(message: String)
case object StartWorkers
case object StartRemoteWorker
case object Done
case object Stop
case object StopAck
case class BitCoin(inputStr: String, outputStr: String)
case class Mine(str: String)

object StringGen {
    def next(s: String): String = {
      val length = s.length
      var c = s.charAt(length - 1)
      if (c == 'z' || c == 'Z') return if (length > 1) next(s.substring(0, length - 1)) + 'A' else "AA"
      s.substring(0, length - 1) + (c.toInt + 1).toChar
    }

    def Increment(s: String, n: Int): String = {
      var i = 0
      var temp = s
      for (i <- 1 to n) {
        temp = next(temp)
      }
      return temp
    }
  }


/**
 * Server:  1. create workers
 *          2. if no workers are present, act as worker !! --> verify
 *          3. if we have found desired number of bitcoins or if TLE terminate
 */
class ServerActor(zeroes: Int, workers: Int, chunk: Int, limit: Int) extends Actor {
    
    var workerPool = new Array[ActorRef](workers)
    var bitcoins = 0
    var strcounter = 0
    var results: Map[String, String] = Map()
    var stopcount = 0
    var actors = workers
    var currstr = "A"
    
    def receive = {
        
        case "getparams" => println("zeroes: " + zeroes + " workers: "+ workers + " chunk: "+ chunk + " limit: "+limit)
        case StartWorkers => startWorkers()
        case StartRemoteWorker => if (strcounter < limit) {
                                    actors += 1
                                    sender ! Mine(currstr)
                                    currstr = StringGen.Increment(currstr, chunk)
                                    strcounter += chunk
                                  } else{
                                      sender ! Stop
                                  }
        case Done => if (strcounter < limit){
                                sender ! Mine(currstr)
                                currstr = StringGen.Increment(currstr, chunk)
                                strcounter += chunk
                            }else{
                                sender ! Stop
                            }
        case BitCoin(inputstr, hashstr) => results += (inputstr -> hashstr)
                                            println(inputstr + "\t" + hashstr)
        case StopAck => stopcount += 1
                        if (stopcount == workers){
                            context.system.shutdown
                        }
        case _ => println("default case!!")
    }
    
    def startWorkers() = {
        val i = 1
        for (i <- 1 to workers){
            workerPool(i-1) = context.actorOf(Props[WorkerActor], name="Worker"+i)
        }
        for (i <- 1 to workers){
            workerPool(i-1) ! setParams(zeroes, chunk, limit)
            workerPool(i-1) ! Mine(currstr)
            currstr = StringGen.Increment(currstr, chunk)
            strcounter += chunk
        }
    }
    
}

/**
 * Worker:  1. generates a random string
 *          2. hash the string using sha-256
 *          3. verify if the hashed string has 'k' leading zeroes -> we found bitcoin
 */
class WorkerActor extends Actor {
    var zeroes = 0
    var chunk = 0
    var limit = 0
    var workername = self.path.name
    
    def receive = {
        
        case setParams(z, c, l) =>  zeroes = z
                                    chunk = c
                                    limit = l
        case Mine(str) =>  mine(str, sender)
                        //println("Done: " + workername)
                        sender ! Done
        case Stop =>  //println("Stop: "+ workername)
                        sender ! StopAck
                        context.stop(self)
        case _ => println("default case!!")
    }
    
    object Hex {

      def valueOf(buf: Array[Byte]): String = buf.map("%02X" format _).mkString
    
    }
    
    def mine(str: String, sender: ActorRef) = {
        var randstr = str //randomStringGenerator()
        for (i <- 1 to chunk) {
            var inputstr = "sandom;"+randstr
            var hash = sha256(inputstr)
			//println(hash.getOrElse(""))
            if(isValidHash(hash)){
                sender ! BitCoin(inputstr, hash.getOrElse(""))
            }
            randstr = StringGen.next(randstr)
        }
    }
    
    
    def sha256 (s: String): Option[String] = {
        //println("hash this: "+s)
        val msg = MessageDigest.getInstance("SHA-256").digest(s.getBytes)
        val hash: Option[String] = Option(msg).map(Hex.valueOf)
        return hash
    }
    
    def isValidHash(hash: Option[String]): Boolean = {
        val str: String = hash.getOrElse("") //to convert Option[String] to String
        for (i <- 1 to zeroes){
            if (str.charAt(i-1)!='0') {
                return false
            }
        }
        return true
    }
    
    def randomStringGenerator(): String = {
        val length = util.Random.nextInt(10) + 1
        val chars = ('a' to 'z') ++ ('A' to 'Z')
        val sb = new StringBuilder
        for (i <- 1 to length) {
          val randomNum = util.Random.nextInt(chars.length)
          sb.append(chars(randomNum))
        }
        //println("randomStr: " + sb.toString())
        return sb.toString()
    }
}


object Miner {
    
    
    def main(args: Array[String]) {
        val chunk = 100000 //worker chunk size
        val limit = 10000000 //threshold
        var zeroes = 1;  //leading zeroes
        val workers = 5; //workers
        var ipAddress = ""
        
        // exit if argument not passed as command line param
        if (args.length < 1) {
          println("Invalid no of args")
          System.exit(1)
        } else if (args.length == 1) {
            args(0) match {
                case s: String if s.contains(".") =>
                        ipAddress = s
                        val remoteSystem = ActorSystem("RemoteMinerSystem", ConfigFactory.load(ConfigFactory.parseString("""
                      akka {
                    actor {
                      provider = "akka.remote.RemoteActorRefProvider"
                    }
                    remote {
                      enabled-transports = ["akka.remote.netty.tcp"]
                      netty.tcp {
                        port = 13000
                      }
                   }
                  }""")))
                  
                  val worker = remoteSystem.actorOf(Props(new WorkerActor()), name = "Samantha")
                  val watcher = remoteSystem.actorOf(Props(new Watcher()), name = "Watcher")

                  watcher ! Watcher.WatchMe(worker)

                  val master = remoteSystem.actorSelection("akka.tcp://MinerSystem@" + ipAddress + ":12000/user/server")
                  println("Successfully connected to server#####################################################")
                  master.tell(StartRemoteWorker, worker)
                  
                 case s: String =>
                      zeroes = s.toInt
                      println("Num of zeroes:" + zeroes)
            
                      val system = ActorSystem.create("MinerSystem", ConfigFactory.load(ConfigFactory.parseString("""{ 
                        "akka" : { "actor" : { "provider" : "akka.remote.RemoteActorRefProvider" }, 
                        "remote" : { "enabled-transports" : [ "akka.remote.netty.tcp" ], "netty" : { "tcp" : { "port" : 12000 } } } } } """)))
                      
                        val serverActor = system.actorOf(Props(new ServerActor(zeroes, workers, chunk, limit)), name="server")
                        serverActor ! "getparams"        
                        serverActor ! StartWorkers

                 case _ => System.exit(1)
            }
        }
        
        object Watcher {
            // Used by others to register an Actor for watching
            case class WatchMe(ref: ActorRef)
        }

        class Watcher extends Actor {
            import Watcher._
    
            // Keep track of what we're watching
            val watched = ArrayBuffer.empty[ActorRef]
    
            // Watch and check for termination
            final def receive = {
                case WatchMe(ref) =>
                    context.watch(ref)
                    watched += ref
                case Terminated(ref) =>
                    watched -= ref
                    if (watched.isEmpty) {
                        context.system.shutdown
                    }
            }
        }
        
    }
        
}
