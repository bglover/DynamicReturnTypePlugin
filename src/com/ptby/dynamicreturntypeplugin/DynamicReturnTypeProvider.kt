package com.ptby.dynamicreturntypeplugin

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider2
import com.ptby.dynamicreturntypeplugin.config.ConfigState
import com.ptby.dynamicreturntypeplugin.config.ConfigStateContainer
import com.ptby.dynamicreturntypeplugin.gettype.GetTypeResponse
import com.ptby.dynamicreturntypeplugin.gettype.GetTypeResponseFactory
import com.ptby.dynamicreturntypeplugin.index.ClassConstantAnalyzer
import com.ptby.dynamicreturntypeplugin.index.FieldReferenceAnalyzer
import com.ptby.dynamicreturntypeplugin.index.LocalClassImpl
import com.ptby.dynamicreturntypeplugin.index.ReturnInitialisedSignatureConverter
import com.ptby.dynamicreturntypeplugin.index.VariableAnalyser
import com.ptby.dynamicreturntypeplugin.json.ConfigAnalyser
import com.ptby.dynamicreturntypeplugin.scanner.FunctionCallReturnTypeScanner
import com.ptby.dynamicreturntypeplugin.scanner.MethodCallReturnTypeScanner
import com.ptby.dynamicreturntypeplugin.signatureconversion.BySignatureSignatureSplitter
import com.ptby.dynamicreturntypeplugin.signatureconversion.CustomSignatureProcessor
import com.ptby.dynamicreturntypeplugin.typecalculation.CallReturnTypeCalculator

import java.util.ArrayList

import com.intellij.openapi.diagnostic.Logger.getInstance
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.impl.FunctionImpl
import com.jetbrains.php.lang.psi.elements.Variable
import com.jetbrains.php.lang.psi.elements.Method
import com.ptby.dynamicreturntypeplugin.symfony.SymfonyContainerLookup
import com.ptby.dynamicreturntypeplugin.symfony.SymfonySignatureTranslator
import com.ptby.dynamicreturntypeplugin.signature_processingv2.GetBySignature
import com.ptby.dynamicreturntypeplugin.signatureconversion.SignatureMatcher

public class DynamicReturnTypeProvider : PhpTypeProvider2 {
    private val classConstantAnalyzer: ClassConstantAnalyzer
    private val getTypeResponseFactory: GetTypeResponseFactory
    private val returnInitialisedSignatureConverter: ReturnInitialisedSignatureConverter
    private val logger = getInstance("DynamicReturnTypePlugin")
    private val fieldReferenceAnalyzer: FieldReferenceAnalyzer
    private val symfonySignatureTranslator = SymfonySignatureTranslator(SymfonyContainerLookup())

    private var variableAnalyser: VariableAnalyser
    {
        val configState = ConfigStateContainer.configState

        val configAnalyser = configState.configAnalyser
        fieldReferenceAnalyzer = FieldReferenceAnalyzer(configAnalyser)
        classConstantAnalyzer = ClassConstantAnalyzer()
        variableAnalyser = VariableAnalyser(configAnalyser, classConstantAnalyzer)
        returnInitialisedSignatureConverter = ReturnInitialisedSignatureConverter()
        variableAnalyser = VariableAnalyser(configAnalyser, classConstantAnalyzer)
        getTypeResponseFactory = createGetTypeResponseFactory(configAnalyser)
    }

    class object {
        public val PLUGIN_IDENTIFIER_KEY: Char = "Ђ".toCharArray()[0]
        public val PLUGIN_IDENTIFIER_KEY_STRING: String = String(charArray(PLUGIN_IDENTIFIER_KEY))
        public val PARAMETER_SEPARATOR: String = "ª"
    }


    private fun createGetTypeResponseFactory(configAnalyser: ConfigAnalyser): GetTypeResponseFactory {
        val callReturnTypeCalculator = CallReturnTypeCalculator()
        val functionCallReturnTypeScanner = FunctionCallReturnTypeScanner(callReturnTypeCalculator)
        val methodCallReturnTypeScanner = MethodCallReturnTypeScanner(callReturnTypeCalculator)

        return GetTypeResponseFactory(configAnalyser, methodCallReturnTypeScanner, functionCallReturnTypeScanner)
    }


    override fun getKey(): Char {
        return PLUGIN_IDENTIFIER_KEY
    }


    override fun getType(psiElement: PsiElement): String? {
        try {
            val dynamicReturnType = getTypeResponseFactory.createDynamicReturnType(psiElement)
            if (dynamicReturnType.isNull()) {
                return null
            }

            return dynamicReturnType.toString()
        } catch (e: Exception) {
            if (e !is ProcessCanceledException) {
                logger.error("Exception", e)
                e.printStackTrace()
            }
        }

        return null
    }

    override fun getBySignature(signature: String, project: Project): Collection<PhpNamedElement>? {
        val getBySignature = GetBySignature( SignatureMatcher(),classConstantAnalyzer )

        return getBySignature.getBySignature(signature, project)
    }


    fun getBySignature2(signature: String, project: Project): Collection<PhpNamedElement>? {
        println(signature)


        var filteredSignature = signature
        if ( filteredSignature.contains("Ő")) {
            //#M#Ő#M#C\TestController.getƀservice_broker:getServiceWithoutMask:#K#C\DynamicReturnTypePluginTestEnvironment\TestClasses\TestService.CLASS_NAME
            filteredSignature = symfonySignatureTranslator.trySymfonyContainer(project, signature)
        }

        if (filteredSignature.contains("[]")) {
            val customList = ArrayList<PhpNamedElement>()
            customList.add(LocalClassImpl(PhpType().add(filteredSignature), project))

            return customList
        }

        val bySignatureSignatureSplitter = BySignatureSignatureSplitter()
        var bySignature: Collection<PhpNamedElement>? = null
        var lastFqnName = ""
        for (chainedSignature in bySignatureSignatureSplitter.createChainedSignatureList(filteredSignature)) {
            val newSignature = lastFqnName + chainedSignature
            bySignature = processSingleSignature(newSignature, project)

            if (bySignature != null && bySignature!!.iterator().hasNext()) {
                lastFqnName = "#M#C" + bySignature!!.iterator().next().getFQN()
            }
        }

        return bySignature
    }


    private fun processSingleSignature(signature: String, project: Project): Collection<PhpNamedElement>? {
        val bySignature: Collection<PhpNamedElement>?
        val customSignatureProcessor = CustomSignatureProcessor(
                returnInitialisedSignatureConverter,
                classConstantAnalyzer,
                fieldReferenceAnalyzer,
                variableAnalyser
        )

        bySignature = customSignatureProcessor.getBySignature(signature, project)
        return bySignature
    }


}
