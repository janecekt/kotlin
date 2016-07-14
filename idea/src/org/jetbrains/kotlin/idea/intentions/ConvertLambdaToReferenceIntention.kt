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
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.core.isVisible
import org.jetbrains.kotlin.idea.inspections.IntentionBasedInspection
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContext.REFERENCE_TARGET
import org.jetbrains.kotlin.resolve.descriptorUtil.hasDefaultValue
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.types.isDynamic
import org.jetbrains.kotlin.types.isFlexible
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter

class ConvertLambdaToReferenceInspection : IntentionBasedInspection<KtLambdaExpression>(ConvertLambdaToReferenceIntention())

class ConvertLambdaToReferenceIntention : SelfTargetingOffsetIndependentIntention<KtLambdaExpression>(
        KtLambdaExpression::class.java, "Convert lambda to reference"
) {
    private fun isConvertableCallInLambda(
            callableExpression: KtExpression,
            explicitReceiver: KtExpression? = null,
            lambdaExpression: KtLambdaExpression,
            context: BindingContext
    ): Boolean {
        val calleeReferenceExpression = when (callableExpression) {
            is KtCallExpression -> callableExpression.calleeExpression as? KtNameReferenceExpression ?: return false
            is KtNameReferenceExpression -> callableExpression
            else -> return false
        }
        val calleeDescriptor = context[REFERENCE_TARGET, calleeReferenceExpression] as? CallableMemberDescriptor ?: return false
        if (calleeDescriptor.typeParameters.isNotEmpty()) return false
        if (calleeDescriptor is SyntheticJavaPropertyDescriptor) return false
        val descriptorHasReceiver = with (calleeDescriptor) {
            if (dispatchReceiverParameter != null && extensionReceiverParameter != null) return false
            dispatchReceiverParameter != null || extensionReceiverParameter != null
        }
        val callHasReceiver = explicitReceiver != null
        if (descriptorHasReceiver != callHasReceiver) return false
        val callableArgumentsCount = if (callableExpression is KtCallExpression) callableExpression.valueArguments.size else 0
        if (calleeDescriptor.valueParameters.size != callableArgumentsCount) return false

        val hasSpecification = lambdaExpression.functionLiteral.hasParameterSpecification()
        val receiverShift = if (callHasReceiver) 1 else 0
        val parametersCount = if (hasSpecification) lambdaExpression.valueParameters.size else 1
        if (parametersCount != callableArgumentsCount + receiverShift) return false
        if (explicitReceiver != null) {
            if (explicitReceiver !is KtNameReferenceExpression) return false
            val callReceiverDescriptor = context[REFERENCE_TARGET, explicitReceiver] as? ParameterDescriptor ?: return false
            val receiverType = callReceiverDescriptor.type
            if (receiverType.isTypeParameter() || receiverType.isFlexible() || receiverType.isError || receiverType.isDynamic() ||
                !receiverType.constructor.isDenotable || receiverType.isFunctionType) return false
            val receiverDeclarationDescriptor = receiverType.constructor.declarationDescriptor
            if (receiverDeclarationDescriptor is ClassDescriptor) {
                if (receiverDeclarationDescriptor.kind == ClassKind.OBJECT) return false
                if (!receiverDeclarationDescriptor.isVisible(
                        explicitReceiver, null, context, explicitReceiver.getResolutionFacade()
                )) return false
            }

            val parameterName = if (hasSpecification) lambdaExpression.valueParameters[0].name else "it"
            if (explicitReceiver.getReferencedName() != parameterName) return false
        }
        if (callableExpression is KtCallExpression) {
            callableExpression.valueArguments.forEachIndexed { i, argument ->
                val argumentExpression = argument.getArgumentExpression() as? KtNameReferenceExpression ?: return false
                val parameterName = if (hasSpecification) lambdaExpression.valueParameters[i + receiverShift].name else "it"
                if (argumentExpression.getReferencedName() != parameterName) return false
            }
        }
        return true
    }

    override fun isApplicableTo(element: KtLambdaExpression): Boolean {
        val body = element.bodyExpression ?: return false
        val statement = body.statements.singleOrNull() ?: return false
        val lambdaParent = element.parent
        val context: BindingContext
        if (lambdaParent is LambdaArgument) {
            val outerCallExpression = lambdaParent.parent as? KtCallExpression ?: return false
            context = outerCallExpression.analyze()
            val outerCallee = outerCallExpression.calleeExpression as? KtReferenceExpression ?: return false
            val outerCalleeDescriptor = context[REFERENCE_TARGET, outerCallee] as? FunctionDescriptor ?: return false
            if (outerCalleeDescriptor.valueParameters.any { it.hasDefaultValue() }) return false
        }
        else {
            context = statement.analyze()
        }
        return when (statement) {
            is KtCallExpression -> {
                isConvertableCallInLambda(callableExpression = statement, lambdaExpression = element, context = context)
            }
            is KtNameReferenceExpression -> false // Global property reference is not possible (?!)
            is KtDotQualifiedExpression -> {
                val selector = statement.selectorExpression ?: return false
                isConvertableCallInLambda(callableExpression = selector, explicitReceiver = statement.receiverExpression,
                                          lambdaExpression = element, context = context)
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
                val selectorReferenceName = when (selector) {
                    is KtCallExpression -> selector.getCallReferencedName() ?: return null
                    is KtNameReferenceExpression -> selector.getReferencedName()
                    else -> return null
                }
                val receiver = expression.receiverExpression as? KtNameReferenceExpression ?: return null
                val context = receiver.analyze()
                val receiverDescriptor = context[REFERENCE_TARGET, receiver] as? ParameterDescriptor ?: return null
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
                        arguments.joinToString(separator = ", ", prefix = "(") { it.text } +
                        ", $referenceName)"
                )
            }
            argumentList?.delete()
            lambdaArgument.replace(newArgumentList)
        }
    }
}