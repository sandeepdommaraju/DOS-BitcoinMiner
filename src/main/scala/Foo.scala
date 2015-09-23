import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }


class HelloActor extends Actor {
  def receive = {
    case "hello" => println("hello back at you - sandeep!!")
    case _       => println("huh? - wtf!")
  }
}
 
object Foo extends App {
  val system = ActorSystem("HelloSystem")
  // default Actor constructor
  val helloActor = system.actorOf(Props[HelloActor], name = "helloactor")
  helloActor ! "hello"
  helloActor ! "buenos dias"
}
