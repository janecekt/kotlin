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
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.inspections.IntentionBasedInspection
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext

class ConvertLambdaToReferenceInspection : IntentionBasedInspection<KtLambdaExpression>(ConvertLambdaToReferenceIntention())

class ConvertLambdaToReferenceIntention : SelfTargetingOffsetIndependentIntention<KtLambdaExpression>(
        KtLambdaExpression::class.java, "Convert lambda to reference"
) {
    override fun isApplicableTo(element: KtLambdaExpression): Boolean {
        val body = element.bodyExpression ?: return false
        val statement = body.statements.singleOrNull() ?: return false
        return when (statement) {
            is KtCallExpression -> {
                val calleeExpression = statement.calleeExpression as? KtNameReferenceExpression ?: return false
                val context = calleeExpression.analyze()
                val descriptor = (context[BindingContext.REFERENCE_TARGET, calleeExpression] as? FunctionDescriptor) ?: return false
                if (descriptor.typeParameters.isNotEmpty()) return false
                if (descriptor.dispatchReceiverParameter != null || descriptor.extensionReceiverParameter != null) return false

                val hasSpecification = element.functionLiteral.hasParameterSpecification()
                val parametersCount = if (hasSpecification) element.valueParameters.size else 1
                if (parametersCount != statement.valueArguments.size) return false
                statement.valueArguments.forEachIndexed { i, argument ->
                    val argumentExpression = argument.getArgumentExpression() as? KtNameReferenceExpression ?: return false
                    val parameterName = if (hasSpecification) element.valueParameters[i].name else "it"
                    if (argumentExpression.getReferencedName() != parameterName) return false
                }
                true
            }
            is KtNameReferenceExpression -> false // Global property reference is not possible (?!)
            is KtQualifiedExpression -> false // Later
            else -> false
        }
    }

    override fun applyTo(element: KtLambdaExpression, editor: Editor?) {
        val body = element.bodyExpression ?: return
        val callExpression = body.statements.singleOrNull() as? KtCallExpression ?: return
        val name = (callExpression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
        val factory = KtPsiFactory(editor?.project)
        val lambdaArgument = element.parent as? KtLambdaArgument
        if (lambdaArgument == null) {
            val callableReferenceExpr = factory.createCallableReferenceExpression(name) ?: return
            element.replace(callableReferenceExpr)
        }
        else {
            val outerCallExpression = lambdaArgument.parent as? KtCallExpression ?: return
            val argumentList = outerCallExpression.valueArgumentList
            val arguments = outerCallExpression.valueArguments.filter { it !is KtLambdaArgument }
            val newArgumentList = if (argumentList == null || arguments.isEmpty()) {
                factory.createCallArguments("(::$name)")
            }
            else {
                factory.createCallArguments(
                        arguments.joinToString(separator = ", ", prefix = "(") +
                        ", ::$name)"
                )
            }
            argumentList?.delete()
            lambdaArgument.replace(newArgumentList)
        }
    }
}