package zio.test

import zio.{ZIO, ZLayer}

private[test] trait SpecVersionSpecific[-R, +E] { self: Spec[R, E] =>

  /**
   * Automatically assembles a layer for the spec, translating it up a level.
   */
  inline def provide[E1 >: E](inline layer: ZLayer[_, E1, _]*): Spec[Any, E1] =
    ${ SpecLayerMacros.provideImpl[Any, R, E1]('self, 'layer) }

  /**
   * Splits the environment into two parts, providing each test with one part
   * using the specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val spec: ZSpec[Clock with Random, Nothing] = ???
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val spec2: ZSpec[Random, Nothing] = spec.provideSome[Random](clockLayer)
   * }}}
   */
  def provideSome[R0] =
    new ProvideSomePartiallyApplied[R0, R, E](self)

  /**
   * Equivalent to [[provideSome]], but does not require providing the remainder
   * type
   *
   * {{{
   * val spec: ZSpec[Clock with Random, Nothing] = ???
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val spec2 = spec.provideSome(clockLayer) // Inferred type is ZSpec[Random, Nothing]
   * }}}
   */
  inline transparent def provideSomeAuto[E1 >: E](inline layer: ZLayer[_, E1, _]*): Spec[_, E1] =
    ${ SpecLayerMacros.provideAutoImpl[R, E1]('self, 'layer) }

  /**
   * Splits the environment into two parts, providing all tests with a shared
   * version of one part using the specified layer and leaving the remainder
   * `R0`.
   *
   * {{{
   * val spec: ZSpec[Int with Random, Nothing] = ???
   * val intLayer: ZLayer[Any, Nothing, Int] = ???
   *
   * val spec2 = spec.provideSomeShared[Random](intLayer)
   * }}}
   */
  def provideSomeShared[R0] =
    new ProvideSomeSharedPartiallyApplied[R0, R, E](self)

  /**
   * Automatically constructs the part of the environment that is not part of
   * the `TestEnvironment`, leaving an effect that only depends on the
   * `TestEnvironment`. This will also satisfy transitive `TestEnvironment`
   * requirements with `TestEnvironment.any`, allowing them to be provided
   * later.
   *
   * {{{
   * val zio: ZIO[OldLady with Console, Nothing, Unit] = ???
   * val oldLadyLayer: ZLayer[Fly, Nothing, OldLady] = ???
   * val flyLayer: ZLayer[Blocking, Nothing, Fly] = ???
   *
   * // The TestEnvironment you use later will provide both Blocking to flyLayer and
   * // Console to zio
   * val zio2 : ZIO[TestEnvironment, Nothing, Unit] =
   *   zio.provideCustom(oldLadyLayer, flyLayer)
   * }}}
   */
  @deprecated("use provide", "2.0.2")
  inline def provideCustom[E1 >: E](inline layer: ZLayer[_, E1, _]*): Spec[TestEnvironment, E1] =
    ${ SpecLayerMacros.provideImpl[TestEnvironment, R, E1]('self, 'layer) }

  /**
   * Automatically assembles a layer for the spec, sharing services between all
   * tests.
   */
  inline def provideShared[E1 >: E](inline layer: ZLayer[_, E1, _]*): Spec[Any, E1] =
    ${ SpecLayerMacros.provideSharedImpl[Any, R, E1]('self, 'layer) }

  /**
   * Automatically constructs the part of the environment that is not part of
   * the `TestEnvironment`, leaving an effect that only depends on the
   * `TestEnvironment`, sharing services between all tests.
   *
   * This will also satisfy transitive `TestEnvironment` requirements with
   * `TestEnvironment.any`, allowing them to be provided later.
   *
   * {{{
   * val zio: ZIO[OldLady with Console, Nothing, Unit] = ???
   * val oldLadyLayer: ZLayer[Fly, Nothing, OldLady] = ???
   * val flyLayer: ZLayer[Blocking, Nothing, Fly] = ???
   *
   * // The TestEnvironment you use later will provide both Blocking to flyLayer and
   * // Console to zio
   * val zio2 : ZIO[TestEnvironment, Nothing, Unit] =
   *   zio.provideCustom(oldLadyLayer, flyLayer)
   * }}}
   */
  @deprecated("use provideShared", "2.0.2")
  inline def provideCustomShared[E1 >: E](inline layer: ZLayer[_, E1, _]*): Spec[TestEnvironment, E1] =
    ${ SpecLayerMacros.provideSharedImpl[TestEnvironment, R, E1]('self, 'layer) }
}

final class ProvideSomePartiallyApplied[R0, -R, +E](val self: Spec[R, E]) extends AnyVal {
  inline def apply[E1 >: E](inline layer: ZLayer[_, E1, _]*): Spec[R0, E1] =
    ${ SpecLayerMacros.provideSomeImpl[R0, R, E1]('self, 'layer) } // TODO:
}

final class ProvideSomeSharedPartiallyApplied[R0, -R, +E](val self: Spec[R, E]) extends AnyVal {
  inline def apply[E1 >: E](inline layer: ZLayer[_, E1, _]*): Spec[R0, E1] =
    ${ SpecLayerMacros.provideSomeSharedImpl[R0, R, E1]('self, 'layer) }
}
