/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference

import akka.annotation.InternalApi
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import org.reactivestreams.{ Processor, Publisher, Subscriber, Subscription }

import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[stream] object StreamLayout {

  // compile-time constant
  final val Debug = false

  /**
   * This is the only extension point for the sealed type hierarchy: composition
   * (i.e. the module tree) is managed strictly within this file, only leaf nodes
   * may be declared elsewhere.
   */
  trait AtomicModule[+S <: Shape, +M] extends Graph[S, M]
}

/**
 * INTERNAL API
 */
@InternalApi private[stream] object VirtualProcessor {
  case object Inert {
    val subscriber = new CancellingSubscriber[Any]
  }
  case class Both(subscriber: Subscriber[Any])
  object Both {
    def create(s: Subscriber[_]) = Both(s.asInstanceOf[Subscriber[Any]])
  }

  case class Establishing(sub: Subscriber[Any])
  object Establishing {
    def create(s: Subscriber[_]) = Establishing(s.asInstanceOf[Subscriber[Any]])
  }
}

/**
 * INTERNAL API
 *
 * This is a transparent processor that shall consume as little resources as
 * possible. Due to the possibility of receiving uncoordinated inputs from both
 * downstream and upstream, this needs an atomic state machine which looks a
 * little like this:
 *
 *            +--------------+      (2)    +------------+
 *            |     null     | ----------> | Subscriber |
 *            +--------------+             +------------+
 *                   |                           |
 *               (1) |                           | (1)
 *                  \|/                         \|/
 *            +--------------+      (2)    +------------+ --\
 *            | Subscription | ----------> |    Both    |    | (4)
 *            +--------------+             +------------+ <-/
 *                   |                           |
 *               (3) |                           | (3)
 *                  \|/                         \|/
 *            +--------------+      (2)    +------------+ --\
 *            |   Publisher  | ----------> |   Inert    |    | (4, *)
 *            +--------------+             +------------+ <-/
 *
 * The idea is to keep the major state in only one atomic reference. The actions
 * that can happen are:
 *
 *  (1) onSubscribe
 *  (2) subscribe
 *  (3) onError / onComplete
 *  (4) onNext
 *      (*) Inert can be reached also by cancellation after which onNext is still fine
 *          so we just silently ignore possible spec violations here
 *
 * Any event that occurs in a state where no matching outgoing arrow can be found
 * is a spec violation, leading to the shutdown of this processor (meaning that
 * the state is updated such that all following actions match that of a failed
 * Publisher or a cancelling Subscriber, and the non-guilty party is informed if
 * already connected).
 *
 * request() can only be called after the Subscriber has received the Subscription
 * and that also means that onNext() will only happen after having transitioned into
 * the Both state as well. The Publisher state means that if the real
 * Publisher terminates before we get the Subscriber, we can just forget about the
 * real one and keep an already finished one around for the Subscriber.
 *
 * The Subscription that is offered to the Subscriber must cancel the original
 * Publisher if things go wrong (like `request(0)` coming in from downstream) and
 * it must ensure that we drop the Subscriber reference when `cancel` is invoked.
 */
@InternalApi private[stream] final class VirtualProcessor[T] extends AtomicReference[AnyRef] with Processor[T, T] {
  import ReactiveStreamsCompliance._
  import VirtualProcessor._

  override def toString: String = s"VirtualProcessor(${this.hashCode()})"
  println(s"created: $this")

  override def subscribe(s: Subscriber[_ >: T]): Unit = {
    @tailrec def rec(sub: Subscriber[Any]): Unit = {
      get() match {
        case null ⇒
          println(s"$this(null).subscribe.rec($s) moving to sub")
          if (!compareAndSet(null, s)) rec(sub)
        case subscription: Subscription ⇒
          println(s"$this($subscription).subscribe.rec($s) moving to both")
          val establishing = Establishing(sub)
          if (compareAndSet(subscription, establishing)) establishSubscription(establishing, subscription)
          else rec(sub)
        case pub: Publisher[_] ⇒
          println(s"$this($pub).subscribe.rec($s) moving go inert")
          if (compareAndSet(pub, Inert)) pub.subscribe(sub)
          else rec(sub)
        case _ ⇒
          println(s"$this(_).subscribe.rec($s)")
          rejectAdditionalSubscriber(sub, "VirtualProcessor")
      }
    }

    if (s == null) {
      val ex = subscriberMustNotBeNullException
      try rec(Inert.subscriber)
      finally throw ex // must throw NPE, rule 2:13
    } else rec(s.asInstanceOf[Subscriber[Any]])
  }

  override final def onSubscribe(s: Subscription): Unit = {
    @tailrec def rec(obj: AnyRef): Unit = {
      get() match {
        case null ⇒
          println(s"$this(null).onSubscribe.rec($s) moving to ${obj.getClass}")
          if (!compareAndSet(null, obj)) rec(obj)
        case subscriber: Subscriber[_] ⇒
          println(s"$this($subscriber).onSubscribe.rec($s) moving to both")
          obj match {
            case subscription: Subscription ⇒
              val establishing = Establishing.create(subscriber)
              if (compareAndSet(subscriber, Both.create(subscriber))) establishSubscription(establishing, subscription)
              else rec(obj)
            case pub: Publisher[_] ⇒
              getAndSet(Inert) match {
                case Inert ⇒ // nothing to be done
                case _     ⇒ pub.subscribe(subscriber.asInstanceOf[Subscriber[Any]])
              }
          }
        case _ ⇒
          println(s"$this(_).onSubscribe.rec($s) spec violation")
          // spec violation
          tryCancel(s)
      }
    }

    if (s == null) {
      val ex = subscriptionMustNotBeNullException
      try rec(ErrorPublisher(ex, "failed-VirtualProcessor"))
      finally throw ex // must throw NPE, rule 2:13
    } else rec(s)
  }

  private def establishSubscription(establishing: Establishing, subscription: Subscription): Unit = {
    val wrapped = new WrappedSubscription(subscription)
    try {
      println("establishSubscription(wrapped)")
      establishing.sub.onSubscribe(wrapped)
      // Requests will be only allowed once onSubscribe has returned to avoid reentering on an onNext before
      // onSubscribe completed
      wrapped.ungateDemandAndRequestBuffered()
      set(Both(establishing.sub))
    } catch {
      case NonFatal(ex) ⇒
        set(Inert)
        tryCancel(subscription)
        tryOnError(establishing.sub, ex)
    }
  }

  override def onError(t: Throwable): Unit = {
    /*
     * `ex` is always a reasonable Throwable that we should communicate downstream,
     * but if `t` was `null` then the spec requires us to throw an NPE (which `ex`
     * will be in this case).
     */
    @tailrec def rec(ex: Throwable): Unit =
      get() match {
        case null ⇒
          println(s"$this(null).onError(${t.getMessage})")
          if (!compareAndSet(null, ErrorPublisher(ex, "failed-VirtualProcessor"))) rec(ex)
          else if (t == null) throw ex
        case s: Subscription ⇒
          println(s"$this($s).onError(${t.getMessage})")
          if (!compareAndSet(s, ErrorPublisher(ex, "failed-VirtualProcessor"))) rec(ex)
          else if (t == null) throw ex
        case Both(s) ⇒
          println(s"$this(Both($s)).onError(${t.getMessage})")
          set(Inert)
          try tryOnError(s, ex)
          finally if (t == null) throw ex // must throw NPE, rule 2:13
        case s: Subscriber[_] ⇒ // spec violation
          println(s"$this($s).onError(${t.getMessage})")
          getAndSet(Inert) match {
            case Inert ⇒ // nothing to be done
            case _     ⇒ ErrorPublisher(ex, "failed-VirtualProcessor").subscribe(s)
          }
        case _ ⇒ // spec violation or cancellation race, but nothing we can do
      }

    val ex = if (t == null) exceptionMustNotBeNullException else t
    rec(ex)
  }

  @tailrec override final def onComplete(): Unit = {
    get() match {
      case null ⇒
        println(s"$this(null).onComplete moving to EmptyPublisher")
        if (!compareAndSet(null, EmptyPublisher)) onComplete()
      case s: Subscription ⇒
        println(s"$this($s).onComplete moving to EmptyPublisher")
        if (!compareAndSet(s, EmptyPublisher)) onComplete()
      case b @ Both(s) ⇒
        println(s"$this($s).onComplete moving to Inert")
        set(Inert)
        tryOnComplete(s)
      case s: Subscriber[_] ⇒ // spec violation
        println(s"$this($s).onComplete moving to Inert")
        set(Inert)
        EmptyPublisher.subscribe(s)
      case _: Establishing ⇒
        // keep trying until subscription established and can complete it
        onComplete()
      case _ ⇒
        println(s"$this(_).onComplete spec violation")
      // spec violation or cancellation race, but nothing we can do
    }
  }

  override def onNext(t: T): Unit =
    if (t == null) {
      val ex = elementMustNotBeNullException
      @tailrec def rec(): Unit =
        get() match {
          case x @ (null | _: Subscription) ⇒ if (!compareAndSet(x, ErrorPublisher(ex, "failed-VirtualProcessor"))) rec()
          case s: Subscriber[_]             ⇒ try s.onError(ex) catch { case NonFatal(_) ⇒ } finally set(Inert)
          case Both(s)                      ⇒ try s.onError(ex) catch { case NonFatal(_) ⇒ } finally set(Inert)
          case _                            ⇒ // spec violation or cancellation race, but nothing we can do
        }
      rec()
      throw ex // must throw NPE, rule 2:13
    } else {
      @tailrec def rec(): Unit = {
        println(s"$this.onNext.rec($t)")
        get() match {
          case Both(s) ⇒
            try {
              s.onNext(t)
            } catch {
              case NonFatal(e) ⇒
                set(Inert)
                throw new IllegalStateException("Subscriber threw exception, this is in violation of rule 2:13", e)
            }
          case s: Subscriber[_] ⇒ // spec violation
            val ex = new IllegalStateException(noDemand)
            getAndSet(Inert) match {
              case Inert ⇒ // nothing to be done
              case _     ⇒ ErrorPublisher(ex, "failed-VirtualProcessor").subscribe(s)
            }
            throw ex
          case Inert | _: Publisher[_] ⇒ // nothing to be done
          case other ⇒
            val pub = ErrorPublisher(new IllegalStateException(noDemand), "failed-VirtualPublisher")
            if (!compareAndSet(other, pub)) rec()
            else throw pub.t
        }
      }
      rec()
    }

  private def noDemand = "spec violation: onNext was signaled from upstream without demand"

  object WrappedSubscription {
    sealed trait SubscriptionState { def demand: Long }
    case object PassThrough extends SubscriptionState { override def demand: Long = 0 }
    case class Buffering(demand: Long) extends SubscriptionState

    val NoBufferedDemand = Buffering(0)
  }

  // Extdending AtomicReference to make the hot memory location share the same cache line with the Subscription
  private class WrappedSubscription(real: Subscription)
    extends AtomicReference[WrappedSubscription.SubscriptionState](WrappedSubscription.NoBufferedDemand) with Subscription {
    import WrappedSubscription._

    // Release
    def ungateDemandAndRequestBuffered(): Unit = {
      println("ungateDemandAndRequestBuffered")
      // Ungate demand
      val requests = getAndSet(PassThrough).demand
      // And request buffered demand
      if (requests > 0) real.request(requests)
    }

    override def request(n: Long): Unit = {
      println(s"WrappedSubscription.request($n)")
      if (n < 1) {
        tryCancel(real)
        VirtualProcessor.this.getAndSet(Inert) match {
          case Both(s) ⇒ rejectDueToNonPositiveDemand(s)
          case Inert   ⇒ // another failure has won the race
          case _       ⇒ // this cannot possibly happen, but signaling errors is impossible at this point
        }
      } else {
        // NOTE: At this point, batched requests might not have been dispatched, i.e. this can reorder requests.
        // This does not violate the Spec though, since we are a "Processor" here and although we, in reality,
        // proxy downstream requests, it is virtually *us* that emit the requests here and we are free to follow
        // any pattern of emitting them.
        // The only invariant we need to keep is to never emit more requests than the downstream emitted so far.
        @tailrec def bufferDemand(n: Long): Unit = {
          val current = get()
          if (current eq PassThrough) {
            println(s"WrappedSubscription.bufferDemand($n) passthrough")
            real.request(n)
          } else if (!compareAndSet(current, Buffering(current.demand + n))) {
            println(s"WrappedSubscription.bufferDemand($n) buffering")
            bufferDemand(n)
          }
        }
        bufferDemand(n)
      }
    }
    override def cancel(): Unit = {
      println(s"WrappedSubscription.cancel() moving to Inert")
      VirtualProcessor.this.set(Inert)
      real.cancel()
    }
  }
}

/**
 * INTERNAL API
 *
 * The implementation of `Sink.asPublisher` needs to offer a `Publisher` that
 * defers to the upstream that is connected during materialization. This would
 * be trivial if it were not for materialized value computations that may even
 * spawn the code that does `pub.subscribe(sub)` in a Future, running concurrently
 * with the actual materialization. Therefore we implement a minimal shell here
 * that plugs the downstream and the upstream together as soon as both are known.
 * Using a VirtualProcessor would technically also work, but it would defeat the
 * purpose of subscription timeouts—the subscription would always already be
 * established from the Actor’s perspective, regardless of whether a downstream
 * will ever be connected.
 *
 * One important consideration is that this `Publisher` must not retain a reference
 * to the `Subscriber` after having hooked it up with the real `Publisher`, hence
 * the use of `Inert.subscriber` as a tombstone.
 */
@InternalApi private[impl] class VirtualPublisher[T] extends AtomicReference[AnyRef] with Publisher[T] {
  import ReactiveStreamsCompliance._
  import VirtualProcessor.Inert
  println(s"virtual publisher: $this")
  override def subscribe(subscriber: Subscriber[_ >: T]): Unit = {
    requireNonNullSubscriber(subscriber)
    println(s"$this.subscribe: $subscriber")
    @tailrec def rec(): Unit = {
      get() match {
        case null ⇒
          if (!compareAndSet(null, subscriber)) rec() // retry

        case pub: Publisher[_] ⇒
          if (compareAndSet(pub, Inert.subscriber)) {
            pub.asInstanceOf[Publisher[T]].subscribe(subscriber)
          } else rec() // retry

        case _: Subscriber[_] ⇒
          rejectAdditionalSubscriber(subscriber, "Sink.asPublisher(fanout = false)")
      }
    }
    rec() // return value is boolean only to make the expressions above compile
  }

  @tailrec final def registerPublisher(pub: Publisher[_]): Unit = {
    println(s"$this.registerPublisher: $pub")
    get() match {
      case null ⇒
        if (!compareAndSet(null, pub)) registerPublisher(pub) // retry

      case sub: Subscriber[r] ⇒
        set(Inert.subscriber)
        pub.asInstanceOf[Publisher[r]].subscribe(sub)

      case p: Publisher[_] ⇒
        throw new IllegalStateException(s"internal error, already registered [$p], yet attempted to register 2nd publisher [$pub]!")

      case unexpected ⇒
        throw new IllegalStateException(s"internal error, unexpected state: $unexpected")
    }
  }

  override def toString: String = s"VirtualPublisher(state = ${get()})"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class ProcessorModule[In, Out, Mat](
  val createProcessor: () ⇒ (Processor[In, Out], Mat),
  attributes:          Attributes                     = DefaultAttributes.processor) extends StreamLayout.AtomicModule[FlowShape[In, Out], Mat] {
  val inPort = Inlet[In]("ProcessorModule.in")
  val outPort = Outlet[Out]("ProcessorModule.out")
  override val shape = new FlowShape(inPort, outPort)

  override def withAttributes(attributes: Attributes) = copy(attributes = attributes)
  override def toString: String = f"ProcessorModule [${System.identityHashCode(this)}%08x]"

  override private[stream] def traversalBuilder =
    LinearTraversalBuilder.fromModule(this, attributes).makeIsland(ProcessorModuleIslandTag)
}
