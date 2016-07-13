/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.inspections.IntentionBasedInspection
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext

class ConvertLambdaToReferenceInspection : IntentionBasedInspection<KtLambdaExpression>(ConvertLambdaToReferenceIntention())

class ConvertLambdaToReferenceIntention : SelfTargetingOffsetIndependentIntention<KtLambdaExpression>(
        KtLambdaExpression::class.java, "Convert lambda to reference"
) {
    private fun isConvertableCallInLambda(
            callExpression: KtCallExpression,
            callReceiver: KtExpression?,
            lambdaExpression: KtLambdaExpression,
            context: BindingContext
    ): Boolean {
        val calleeExpression = callExpression.calleeExpression as? KtNameReferenceExpression ?: return false
        val descriptor = (context[BindingContext.REFERENCE_TARGET, calleeExpression] as? FunctionDescriptor) ?: return false
        if (descriptor.typeParameters.isNotEmpty()) return false
        val descriptorHasReceiver = descriptor.dispatchReceiverParameter != null || descriptor.extensionReceiverParameter != null
        val callHasReceiver = callReceiver != null
        if (descriptorHasReceiver != callHasReceiver) return false

        val hasSpecification = lambdaExpression.functionLiteral.hasParameterSpecification()
        val receiverShift = if (callHasReceiver) 1 else 0
        val parametersCount = if (hasSpecification) lambdaExpression.valueParameters.size else 1
        if (parametersCount != callExpression.valueArguments.size + receiverShift) return false
        if (callReceiver != null) {
            if (callReceiver !is KtNameReferenceExpression) return false
            val parameterName = if (hasSpecification) lambdaExpression.valueParameters[0].name else "it"
            if (callReceiver.getReferencedName() != parameterName) return false
        }
        callExpression.valueArguments.forEachIndexed { i, argument ->
            val argumentExpression = argument.getArgumentExpression() as? KtNameReferenceExpression ?: return false
            val parameterName = if (hasSpecification) lambdaExpression.valueParameters[i + receiverShift].name else "it"
            if (argumentExpression.getReferencedName() != parameterName) return false
        }
        return true
    }

    override fun isApplicableTo(element: KtLambdaExpression): Boolean {
        val body = element.bodyExpression ?: return false
        val statement = body.statements.singleOrNull() ?: return false
        return when (statement) {
            is KtCallExpression -> {
                isConvertableCallInLambda(statement, null, element, statement.analyze())
            }
            is KtNameReferenceExpression -> false // Global property reference is not possible (?!)
            is KtDotQualifiedExpression -> {
                val selector = statement.selectorExpression as? KtCallExpression ?: return false
                isConvertableCallInLambda(selector, statement.receiverExpression, element, statement.analyze())
            }
            else -> false
        }
    }

    private fun KtCallExpression.getCallReferencedName() = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

    private fun referenceName(expression: KtExpression): String? {
        return when (expression) {
            is KtCallExpression -> "::${expression.getCallReferencedName()}"
            is KtDotQualifiedExpression -> {
                val selector = expression.selectorExpression
                val selectorReferenceName = (selector as? KtCallExpression)?.let { it.getCallReferencedName() } ?: return null
                val receiver = expression.receiverExpression as? KtNameReferenceExpression ?: return null
                val context = receiver.analyze()
                val receiverDescriptor = (context[BindingContext.REFERENCE_TARGET, receiver] as? ParameterDescriptor) ?: return null
                val receiverType = receiverDescriptor.type
                "$receiverType::$selectorReferenceName"
            }
            else -> null
        }
    }

    override fun applyTo(element: KtLambdaExpression, editor: Editor?) {
        val body = element.bodyExpression ?: return
        val referenceName = referenceName(body.statements.singleOrNull() ?: return) ?: return
        val factory = KtPsiFactory(editor?.project)
        val lambdaArgument = element.parent as? KtLambdaArgument
        if (lambdaArgument == null) {
            val callableReferenceExpr = factory.createCallableReferenceExpression(referenceName) ?: return
            element.replace(callableReferenceExpr)
        }
        else {
            val outerCallExpression = lambdaArgument.parent as? KtCallExpression ?: return
            val argumentList = outerCallExpression.valueArgumentList
            val arguments = outerCallExpression.valueArguments.filter { it !is KtLambdaArgument }
            val newArgumentList = if (argumentList == null || arguments.isEmpty()) {
                factory.createCallArguments("($referenceName)")
            }
            else {
                factory.createCallArguments(
                        arguments.joinToString(separator = ", ", prefix = "(") +
                        ", $referenceName)"
                )
            }
            argumentList?.delete()
            lambdaArgument.replace(newArgumentList)
        }
    }
}