package claims

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LocaleParityTest {

    private fun collectKeys(yamlPath: Path): Set<String> {
        val cfg = YamlConfiguration.loadConfiguration(yamlPath.toFile())
        return cfg.getKeys(true)
            .filter { key -> !cfg.isConfigurationSection(key) }
            .toSet()
    }

    @Test
    fun `messages_en and messages_ko have identical keys`() {
        val resDir = Path.of("src/main/resources")
        val enKeys = collectKeys(resDir.resolve("messages_en.yml"))
        val koKeys = collectKeys(resDir.resolve("messages_ko.yml"))

        assertTrue(enKeys.isNotEmpty(), "messages_en.yml must not be empty")
        assertEquals(emptySet<String>(), enKeys - koKeys, "Missing keys in messages_ko.yml")
        assertEquals(emptySet<String>(), koKeys - enKeys, "Missing keys in messages_en.yml")
    }

    @Test
    fun `messages_en and messages_ko have identical placeholders per key`() {
        val resDir = Path.of("src/main/resources")
        val en = YamlConfiguration.loadConfiguration(resDir.resolve("messages_en.yml").toFile())
        val ko = YamlConfiguration.loadConfiguration(resDir.resolve("messages_ko.yml").toFile())
        val placeholderRegex = Regex("\\{([a-zA-Z0-9_]+)}")

        for (key in collectKeys(resDir.resolve("messages_en.yml"))) {
            val enPlaceholders = placeholderRegex.findAll(en.getString(key).orEmpty()).map { it.groupValues[1] }.toSet()
            val koPlaceholders = placeholderRegex.findAll(ko.getString(key).orEmpty()).map { it.groupValues[1] }.toSet()
            assertEquals(enPlaceholders, koPlaceholders, "Placeholder mismatch for locale key '$key'")
        }
    }

    @Test
    fun `core package does not import any feature modules`() {
        val coreDir = Path.of("src/main/kotlin/claims/core")
        val violations = mutableListOf<String>()
        Files.walk(coreDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                if (file.fileName.toString() != "ClaimsConfig.kt" && text.contains("claims.modules.")) {
                    violations += file.fileName.toString()
                }
            }
        }
        assertEquals(emptyList<String>(), violations, "Core infrastructure files must not depend on feature modules")
    }
}
