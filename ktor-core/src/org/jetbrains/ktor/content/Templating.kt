package org.jetbrains.ktor.content

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.interception.*
import kotlin.reflect.*

interface TemplateEngine<C : Any, R> where R : StreamContent, R : HasContentType {
    val contentClass: KClass<C>
    fun process(content: C): R
}

inline fun <reified C : Any> InterceptApplicationCall<ApplicationCall>.templating(engine: TemplateEngine<C, *>) {
    val javaType = engine.contentClass.java
    intercept { call ->
        call.response.interceptSend { obj, next ->
            if (javaType.isInstance(obj)) {
                call.response.send(engine.process(obj as C))
            } else {
                next(obj)
            }
        }
    }
}
