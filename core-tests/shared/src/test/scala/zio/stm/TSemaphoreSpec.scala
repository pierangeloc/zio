package zio.stm

import zio.{Promise, ZIO, ZIOBaseSpec, durationInt}
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object TSemaphoreSpec extends ZIOBaseSpec {
  override def spec = suite("TSemaphore")(
    suite("factories")(
      test("make") {
        check(Gen.long(1L, Int.MaxValue)) { expected =>
          val actual = for {
            sem <- TSemaphore.make(expected)
            cap <- sem.available
          } yield cap

          assertZIO(actual.commit)(equalTo(expected))
        }
      }
    ),
    suite("acquire and release")(
      test("acquiring and releasing a permit should not change the availability") {
        check(Gen.long(1L, Int.MaxValue)) { expected =>
          val actual = for {
            sem <- TSemaphore.make(expected)
            _   <- sem.acquire *> sem.release
            cap <- sem.available
          } yield cap
          assertZIO(actual.commit)(equalTo(expected))
        }
      },
      test("used capacity must be equal to the # of acquires minus # of releases") {
        check(usedCapacityGen) { case (capacity, acquire, release) =>
          val actual = for {
            sem <- TSemaphore.make(capacity)
            _   <- repeat(sem.acquire)(acquire) *> repeat(sem.release)(release)
            cap <- sem.available
          } yield cap

          val usedCapacity = acquire - release
          assertZIO(actual.commit)(equalTo(capacity - usedCapacity))
        }
      },
      test("acquireN/releaseN(n) is acquire/release repeated N times") {
        check(Gen.long(1, 100)) { capacity =>
          def acquireRelease(
            sem: TSemaphore
          )(acq: Long => STM[Nothing, Unit])(rel: Long => STM[Nothing, Unit]): STM[Nothing, (Long, Long)] =
            for {
              _            <- acq(capacity)
              usedCapacity <- sem.available
              _            <- rel(capacity)
              freeCapacity <- sem.available
            } yield (usedCapacity, freeCapacity)

          STM.atomically {
            for {
              sem              <- TSemaphore.make(capacity)
              acquireReleaseN   = acquireRelease(sem)(sem.acquireN)(sem.releaseN)
              acquireReleaseRep = acquireRelease(sem)(repeat(sem.acquire))(repeat(sem.release))
              resN             <- acquireReleaseN
              resRep           <- acquireReleaseRep
            } yield assert(resN)(equalTo(resRep)) && assert(resN)(equalTo((0L, capacity)))
          }
        }
      },
      test("withPermit automatically releases the permit if the effect is interrupted") {
        for {
          promise   <- Promise.make[Nothing, Unit]
          semaphore <- TSemaphore.make(1).commit
          effect     = semaphore.withPermit(promise.succeed(()) *> ZIO.never)
          fiber     <- effect.fork
          _         <- promise.await
          _         <- fiber.interrupt
          permits   <- semaphore.available.commit
        } yield assert(permits)(equalTo(1L))
      } @@ exceptJS(nonFlaky),
      test("withPermit acquire is interruptible") {
        for {
          semaphore <- TSemaphore.make(0L).commit
          effect     = semaphore.withPermit(ZIO.unit)
          fiber     <- effect.fork
          _         <- fiber.interrupt
        } yield assertCompletes
      } @@ exceptJS(nonFlaky),
      test("withPermitsManaged releases same number of permits") {
        for {
          semaphore <- TSemaphore.make(2L).commit
          _         <- ZIO.scoped(semaphore.withPermitsScoped(2))
          permits   <- semaphore.available.commit
        } yield assertTrue(permits == 2L)
      },
      test("acquireBetween doesn't necessarily give the max requested number of permits") {
        for {
          semaphore <- TSemaphore.make(2L)
          actual    <- semaphore.acquireBetween(0L, 5L)
          remaining <- semaphore.available
        } yield {
          assertTrue(actual == 2L)
          assertTrue(remaining == 0L)
        }
      },
      test("withPermitsBetween returns the number of permits allotted") {
        val maxPermits = 2L
        for {
          semaphore <- TSemaphore.make(maxPermits).commit
          actualAndRemainingInEffect <-
            semaphore.withPermitsBetween(0L, 5L) { (actual: Long) =>
              for (remainingInEffect <- semaphore.available.commit) yield {
                (actual, remainingInEffect)
              }
            }
          remainingWhenComplete <- semaphore.available.commit
        } yield {
          val (actual, remainingInEffect) = actualAndRemainingInEffect
          assertTrue(actual == maxPermits)
          assertTrue(remainingInEffect == 0L)
          assertTrue(remainingWhenComplete == maxPermits)
        }
      },
      test("withPermitsBetween fails if the minimum number of permits is unavailable.") {
        val transaction =
          for {
            semaphore <- TSemaphore.make(2L)
            actual    <- semaphore.acquireBetween(3L, 5L)
          } yield actual
        transaction.commitEither *> assertTrue(false)
      } @@ timeout(1.second) @@ failing,
      test("acquireUpTo") {
        for {
          semaphore <- TSemaphore.make(5L)
          actual    <- semaphore.acquireUpTo(2L)
          remaining <- semaphore.available
        } yield {
          assertTrue(actual == 2L)
          assertTrue(remaining == 3L)
        }
      }
    ),
    suite("tryAcquire, tryAcquireN, tryWithPermit and tryWithPermits")(
      test("tryAcquire should succeed when a permit is available") {
        for {
          sem <- TSemaphore.makeCommit(1L)
          res <- sem.tryAcquire.commit
        } yield assert(res)(isTrue)
      },
      test("tryAcquire should fail when no permits are available") {
        for {
          sem <- TSemaphore.makeCommit(0L)
          res <- sem.tryAcquire.commit
        } yield assert(res)(isFalse)
      },
      test("tryAcquire should decrease the permit count when successful") {
        for {
          sem   <- TSemaphore.makeCommit(1L)
          _     <- sem.tryAcquire.commit
          avail <- sem.available.commit
        } yield assert(avail)(equalTo(0L))
      },
      test("tryAcquireN should acquire permits if enough are available") {
        for {
          sem <- TSemaphore.makeCommit(5L)
          res <- sem.tryAcquireN(3L).commit
        } yield assert(res)(isTrue)
      },
      test("tryAcquireN should fail if not enough permits are available") {
        for {
          sem <- TSemaphore.makeCommit(2L)
          res <- sem.tryAcquireN(3L).commit
        } yield assert(res)(isFalse)
      },
      test("tryAcquireN should decrease the permit count when successful") {
        for {
          sem   <- TSemaphore.makeCommit(5L)
          _     <- sem.tryAcquireN(3L).commit
          avail <- sem.available.commit
        } yield assert(avail)(equalTo(2L))
      },
      test("tryAcquireN should not change permit count when unsuccessful") {
        for {
          sem   <- TSemaphore.makeCommit(2L)
          _     <- sem.tryAcquireN(3L).commit
          avail <- sem.available.commit
        } yield assert(avail)(equalTo(2L))
      },
      test("tryWithPermits should acquire a permit and release it") {
        for {
          sem    <- TSemaphore.makeCommit(2L)
          result <- sem.tryWithPermits(1L)(ZIO.succeed(2))
          avail  <- sem.available.commit
        } yield assertTrue(result.contains(2) && avail == 2L)
      },
      test("tryWithPermits should return None if no permits available") {
        for {
          sem    <- TSemaphore.makeCommit(0L)
          result <- sem.tryWithPermits(1L)(ZIO.succeed(2))
          avail  <- sem.available.commit
        } yield assertTrue(result.isEmpty && avail == 0L)
      },
      test(
        "tryWithPermits should return None if requested amount of permits is greater than available amount of permits"
      ) {
        for {
          sem    <- TSemaphore.makeCommit(3L)
          result <- sem.tryWithPermits(5L)(ZIO.succeed(2))
          avail  <- sem.available.commit
        } yield assertTrue(result.isEmpty && avail == 3L)
      },
      test("tryWithPermit should acquire a permit and release it") {
        for {
          sem    <- TSemaphore.makeCommit(3L)
          result <- sem.tryWithPermit(ZIO.succeed(2))
          avail  <- sem.available.commit
        } yield assertTrue(result.contains(2) && avail == 3L)
      }
    )
  )

  private def repeat[E, A](stm: STM[E, A])(n: Long): STM[E, A] = n match {
    case x if x < 1 => STM.die(new Throwable("n must be greater than 0"))
    case 1          => stm
    case x          => stm *> repeat(stm)(x - 1)
  }

  private val usedCapacityGen: Gen[Any, (Long, Long, Long)] = for {
    capacity <- Gen.long(1L, 1000)
    acquire  <- Gen.long(1L, capacity)
    release  <- Gen.long(1L, acquire)
  } yield (capacity, acquire, release)
}
