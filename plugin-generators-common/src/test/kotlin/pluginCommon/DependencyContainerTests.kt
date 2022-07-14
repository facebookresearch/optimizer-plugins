/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon

import org.junit.jupiter.api.Test
import kotlin.reflect.full.createType

// if I add Bar and Foo is dependent on Bat that is dependent on Bar, then
// the container should be able to instantiate Foo without me adding Bar.
// However, the container should not store the instance of Bar.
class DependencyContainerSuccessTest {
    interface Bar
    class Bat(bar: Bar)
    class BarImpl : Bar
    class Foo(bat: Bat)
    @Test
    fun getSuccess() {
        val container = DependencyContainer()
        container.put<Bar>(BarImpl())

        val foo = container.get<Foo>()

        assert(container.singletonServices.contains(Bat::class.createType()) == false)
        assert(container.singletonServices.contains(Foo::class.createType()) == false)
    }
}

class DependencyContainerGetFailureTest {
    interface Bar
    interface Interface1

    class Bat(bar: Bar, somethingElse: Interface1)
    class Bat2(string: String, int: Int)
    class BarImpl : Bar
    class Target1(bat: Bat)
    class Target2(bar2: Bat2)

    @Test
    fun getFailureOnInterface() {
        val container = DependencyContainer()
        container.put<Bar>(BarImpl())

        try {
            val target1 = container.get<Target1>()
            assert(false, { "An exception should have been thrown because Interface1 cannot be instantiated." })
        } catch (e: PluginCodegenException) {
            println(e.message)
        }
    }

    @Test
    fun getFailureOnPrimitive() {
        val container = DependencyContainer()
        try {
            val target2 = container.get<Target2>()
            assert(false, { "An exception should have been thrown because Interface1 cannot be instantiated." })
        } catch (e: PluginCodegenException) {
        }
    }
}

class DependencyContainerRecursionFailureTest {
    interface Bar
    class BarImpl(b: BarImpl) : Bar
    @Test
    fun recursionFailureOnRecursion() {
        val container = DependencyContainer()
        try {
            val instance = container.get<BarImpl>()
            assert(false, { "An exception should have been thrown because Interface1 cannot be instantiated." })
        } catch (e: PluginCodegenException) {
            assert(e.message?.contains("Recursion!") == true)
        }
    }
}
