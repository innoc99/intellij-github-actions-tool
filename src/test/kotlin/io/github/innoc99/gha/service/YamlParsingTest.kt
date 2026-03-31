package io.github.innoc99.gha.service

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * SnakeYAML로 workflow_dispatch inputs 파싱 테스트
 */
class YamlParsingTest {

    @Test
    fun `SnakeYAML on 키 파싱 테스트`() {
        val yamlContent = """
            name: Manual Tag Creator
            on:
              workflow_dispatch:
                inputs:
                  tag_method:
                    description: '태그 생성 방식'
                    required: true
                    default: 'timestamp'
                    type: choice
                    options:
                      - timestamp
                      - custom
                  custom_tag:
                    description: '커스텀 태그명'
                    required: false
                    type: string
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
        """.trimIndent()

        println("=== YAML 파싱 테스트 시작 ===")

        val yaml = Yaml()
        val loaded = yaml.load<Any>(yamlContent)
        println("yaml.load 결과 타입: ${loaded?.javaClass?.name}")

        @Suppress("UNCHECKED_CAST")
        val doc = loaded as? Map<Any, Any>
        println("doc keys: ${doc?.keys?.map { "${it?.javaClass?.simpleName}:'$it'" }}")

        // on 키 테스트
        val onByString = doc?.get("on")
        val onByTrue = doc?.get(true)
        val onByTrueString = doc?.get("true")
        println("doc[\"on\"] = $onByString (type: ${onByString?.javaClass?.name})")
        println("doc[true] = $onByTrue (type: ${onByTrue?.javaClass?.name})")
        println("doc[\"true\"] = $onByTrueString (type: ${onByTrueString?.javaClass?.name})")

        val onRaw = onByString ?: onByTrue ?: onByTrueString
        println("onRaw = $onRaw (type: ${onRaw?.javaClass?.name})")

        assert(onRaw != null) { "'on' 키를 찾을 수 없음!" }

        val onSection = onRaw as? Map<*, *>
        println("onSection keys: ${onSection?.keys}")
        assert(onSection != null) { "'on' 섹션이 Map이 아님: ${onRaw?.javaClass?.name}" }

        val dispatchRaw = onSection?.get("workflow_dispatch")
        println("dispatchRaw = $dispatchRaw (type: ${dispatchRaw?.javaClass?.name})")
        assert(dispatchRaw != null) { "'workflow_dispatch' 를 찾을 수 없음" }

        val dispatchSection = dispatchRaw as? Map<*, *>
        println("dispatchSection keys: ${dispatchSection?.keys}")
        assert(dispatchSection != null) { "'workflow_dispatch' 가 Map이 아님" }

        val inputsSection = dispatchSection?.get("inputs") as? Map<*, *>
        println("inputsSection keys: ${inputsSection?.keys}")
        assert(inputsSection != null) { "'inputs' 를 찾을 수 없음" }

        println("\n=== Inputs 상세 ===")
        inputsSection?.forEach { (key, value) ->
            val inputMap = value as? Map<*, *>
            println("  $key:")
            println("    description: ${inputMap?.get("description")}")
            println("    type: ${inputMap?.get("type")}")
            println("    required: ${inputMap?.get("required")}")
            println("    default: ${inputMap?.get("default")}")
            println("    options: ${inputMap?.get("options")}")
        }

        println("\n=== 파싱 성공: ${inputsSection?.size}개 입력 필드 ===")
    }
}
