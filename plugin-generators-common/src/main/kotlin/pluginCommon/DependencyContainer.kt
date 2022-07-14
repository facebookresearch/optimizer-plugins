/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.createType

@Suppress("UNCHECKED_CAST")
class DependencyContainer {
    val singletonServices = mutableMapOf<KType, Any>()
    private val failures = mutableSetOf<KType>()

    private fun <T : Any> instanceOf(type: KType): T = instanceOf<T, Unit>(type)

    fun <T : Any, U : Any> instanceOf(key: KType, overriddenArgument: U? = null): T {
        if (failures.contains(key)) throw PluginCodegenException("Recursion! Cannot create an instance of ${key.classifier?.let{(it as? kotlin.reflect.KClass<*>)?.simpleName}}")
        if (singletonServices.containsKey(key)) {
            return singletonServices[key]!! as T
        }
        failures.add(key)
        val providedType = overriddenArgument?.let { it::class.createType() }
        when (val klass = key.classifier) {
            is KClass<*> -> {
                val constructor: KFunction<T> = when (klass.constructors.size) {
                    1 -> klass.constructors.first() as KFunction<T>
                    0 -> {
                        throw PluginCodegenException("Cannot create instance of type ${klass.simpleName} because there are no constructors")
                    }
                    else -> klass.constructors.groupBy { it.parameters.size }.maxByOrNull { it.key }?.value?.firstOrNull() as? KFunction<T>
                        ?: throw PluginCodegenException("Constructor is ambiguous")
                }
                val argumentMap = if (providedType != null) {
                    constructor.parameters.map {
                        val value = if (it.type == providedType) {
                            overriddenArgument
                        } else {
                            instanceOf(it.type)
                        }
                        Pair(it, value)
                    }
                } else {
                    constructor.parameters.map {
                        val value = singletonServices[it.type] ?: instanceOf(it.type)
                        Pair(it, value)
                    }
                }
                try {
                    val arguments = argumentMap.toMap()
                    val instance = constructor.callBy(arguments)
                    failures.remove(key)
                    return instance
                } catch (e: IllegalArgumentException) {
                    throw PluginCodegenException("instantiation of ${key.javaClass.name} failed with ${e.message}")
                } catch (e: InstantiationException) {
                    throw PluginCodegenException("instantiation of ${key.javaClass.name} failed with ${e.message}. If it is an interface, you must explicitly add an implementation")
                } catch (e: kotlin.reflect.jvm.internal.KotlinReflectionInternalError) {
                    throw PluginCodegenException("instantiation of ${key.javaClass.name} failed with ${e.message}. Ensure there are no private constructors.")
                }
            }
            else -> throw PluginCodegenException("Cannot instantiate a type with no kclass because we do not know the constructors")
        }
    }

    inline fun <reified T : Any> get() = instanceOf<T, Unit>(T::class.createType(), null)
    inline fun <reified T : Any, reified U : Any> get(arg: U) = this.instanceOf<T, U>(T::class.createType(), arg)

    inline fun <reified T : Any> put() {
        val singleton = get<T>()
        singletonServices[T::class.createType()] = singleton
    }
    inline fun <reified T : Any> put(instance: T) {
        singletonServices[T::class.createType()] = instance
    }
}
