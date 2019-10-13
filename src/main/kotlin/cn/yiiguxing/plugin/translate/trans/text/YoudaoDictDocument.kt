@file:Suppress("SpellCheckingInspection")

package cn.yiiguxing.plugin.translate.trans.text

import cn.yiiguxing.plugin.translate.trans.YWordFormWrapper
import cn.yiiguxing.plugin.translate.trans.YoudaoTranslation
import cn.yiiguxing.plugin.translate.ui.StyledViewer
import cn.yiiguxing.plugin.translate.util.Settings
import cn.yiiguxing.plugin.translate.util.text.StyledString
import cn.yiiguxing.plugin.translate.util.text.appendString
import cn.yiiguxing.plugin.translate.util.text.getStyleOrAdd
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.tritonus.share.ArraySet
import java.util.*
import javax.swing.text.*

class YoudaoDictDocument private constructor(
    private val wordStrings: List<CharSequence>,
    private val variantStrings: List<CharSequence>,
    override val translations: Set<String>
) : TranslationDocument {

    override val text: String get() = toString()

    override fun setupTo(viewer: StyledViewer) {
        viewer.apply {
            dragEnabled = false
            disableSelection()
            initStyle()
            styledDocument.appendContents()
        }
    }

    private fun StyledViewer.initStyle() {
        val defaultStyle = getStyle(StyleContext.DEFAULT_STYLE)

        styledDocument.getStyleOrAdd(REGULAR_STYLE, defaultStyle) { style ->
            StyleConstants.setForeground(style, JBColor(0x2A237A, 0xA9B7C6))
        }
        styledDocument.getStyleOrAdd(POS_STYLE, defaultStyle) { style ->
            StyleConstants.setForeground(style, JBColor(0x9C27B0, 0xDF7CFF))
        }
        styledDocument.getStyleOrAdd(WORD_STYLE, defaultStyle) { style ->
            StyleConstants.setForeground(style, WORD_COLOR)
        }
        styledDocument.getStyleOrAdd(SEPARATOR_STYLE, defaultStyle) { style ->
            StyleConstants.setForeground(style, JBColor(0xFF5555, 0x2196F3))
        }
        styledDocument.getStyleOrAdd(VARIANT_NAME_STYLE, defaultStyle) { style ->
            StyleConstants.setForeground(style, JBColor(0x067D17, 0xA8C023))
        }

        val metrics = getFontMetrics(font)
        val tabWidth = wordStrings.asSequence()
            .filter { it is StyledString && it.style == POS_STYLE }
            .map { metrics.stringWidth(it.toString()) }
            .max()
            ?: return

        val attrs = SimpleAttributeSet()
        val pos = tabWidth + JBUI.scale(2f)
        StyleConstants.setTabSet(attrs, TabSet(arrayOf(TabStop(pos, TabStop.ALIGN_RIGHT, TabStop.LEAD_NONE))))
        styledDocument.setParagraphAttributes(0, styledDocument.length, attrs, true)
    }

    private fun StyledDocument.appendContents() {
        appendStrings(wordStrings) { wordString ->
            if (wordString.style == POS_STYLE) "\t$wordString\t" else wordString.toString()
        }

        if (variantStrings.isNotEmpty()) {
            appendString("\n\n")
            appendStrings(variantStrings)
        }
    }

    private inline fun StyledDocument.appendStrings(
        strings: List<CharSequence>,
        transform: (StyledString) -> String = { it.toString() }
    ) {
        for (string in strings) {
            if (string is StyledString) {
                val style = getStyle(string.style)
                string.clickDate?.let { data ->
                    val listener = StyledViewer.ColoredMouseListener(WORD_COLOR, WORD_HOVER_COLOR, data)
                    StyledViewer.StyleConstants.setMouseListener(style, listener)
                }
                appendString(transform(string), style)
            } else {
                appendString(string.toString(), REGULAR_STYLE)
            }
        }
    }

    override fun toString(): String {
        val stringBuilder = StringBuilder()
        for (wordString in wordStrings) {
            stringBuilder.append(wordString)
            if (wordString is StyledString && wordString.style == POS_STYLE) {
                stringBuilder.append(' ')
            }
        }

        if (variantStrings.isNotEmpty() && Settings.showWordForms) {
            stringBuilder.append("\n\n")
            for (variantsString in variantStrings) {
                stringBuilder.append(variantsString)
            }
        }

        return stringBuilder.toString()
    }

    enum class EntryType { WORD, VARIANT }

    object Factory : TranslationDocument.Factory<YoudaoTranslation, YoudaoDictDocument> {

        private val REGEX_EXPLANATION =
            Regex("^((a|adj|prep|pron|n|v|conj|s|sc|o|oc|vi|vt|aux|ad|adv|art|num|int|u|c|pl|abbr)\\.)(.+)$")
        private val REGEX_WORDS_SEPARATOR = Regex("[,;，；]")
        private val REGEX_VARIANTS_SEPARATOR = Regex("\\s*或\\s*")
        private val REGEX_WORD_ANNOTATION = Regex("\\(.*?\\)|（.*?）|\\[.*?]|【.*?】|<.*?>")

        private const val GROUP_PART_OF_SPEECH = 1
        private const val GROUP_WORDS = 3

        override fun getDocument(input: YoudaoTranslation): YoudaoDictDocument? {
            val basicExplain = input.basicExplain ?: return null
            val explanations = basicExplain.explains?.takeIf { it.isNotEmpty() } ?: return null
            val variantStrings = getVariantStrings(basicExplain.wordForms)

            val wordStrings = LinkedList<CharSequence>()
            val translations = ArraySet<String>()
            for (i in explanations.indices) {
                if (i > 0) {
                    wordStrings += "\n"
                }

                val explanation = explanations[i]
                val matchResult = REGEX_EXPLANATION.find(explanation)
                val words = if (matchResult != null) {
                    wordStrings += StyledString(matchResult.groups[GROUP_PART_OF_SPEECH]!!.value, POS_STYLE)
                    matchResult.groups[GROUP_WORDS]!!.value.trim()
                } else explanation.trim()

                words.blocks(REGEX_WORDS_SEPARATOR) { separatorOrWord, isSeparator ->
                    if (isSeparator) {
                        val separator = when (separatorOrWord) {
                            ",", ";" -> "$separatorOrWord "
                            else -> separatorOrWord
                        }
                        wordStrings += StyledString(separator, SEPARATOR_STYLE)
                    } else {
                        separatorOrWord.trim().blocks(REGEX_WORD_ANNOTATION) { wordOrAnnotation, isAnnotation ->
                            wordStrings += if (isAnnotation) {
                                wordOrAnnotation
                            } else {
                                translations += wordOrAnnotation
                                StyledString(wordOrAnnotation, WORD_STYLE, EntryType.WORD)
                            }
                        }
                    }
                }
            }

            return YoudaoDictDocument(wordStrings.toList(), variantStrings, translations.toSet())
        }

        private fun getVariantStrings(wordForms: Array<out YWordFormWrapper>?): List<CharSequence> {
            val variantStrings = LinkedList<CharSequence>()
            wordForms?.takeIf { it.isNotEmpty() }?.forEachIndexed { i, wrapper ->
                if (i > 0) {
                    variantStrings += "\n"
                }

                val wordForm = wrapper.wordForm
                variantStrings += StyledString("${wordForm.name}: ", VARIANT_NAME_STYLE)
                wordForm.value.split(REGEX_VARIANTS_SEPARATOR).forEachIndexed { index, value ->
                    if (index > 0) {
                        variantStrings += StyledString(", ", SEPARATOR_STYLE)
                    }
                    variantStrings += StyledString(value, WORD_STYLE, EntryType.VARIANT)
                }
            }

            return variantStrings.toList()
        }

        private fun String.blocks(regex: Regex, onBlock: (block: String, isMatched: Boolean) -> Unit) {
            var cursor = 0
            regex.findAll(this).forEach { matched ->
                val start = matched.range.first
                if (start != 0) {
                    val block = substring(cursor, matched.range.first)
                    onBlock(block, false)
                }
                onBlock(matched.value, true)
                cursor = matched.range.last + 1
            }
            if (cursor < length) {
                onBlock(substring(cursor, length), false)
            }
        }
    }

    companion object {
        private const val REGULAR_STYLE = "yd_dict_regular"
        private const val POS_STYLE = "yd_dict_part_of_speech"
        private const val WORD_STYLE = "yd_dict_word"
        private const val SEPARATOR_STYLE = "yd_dict_separator"
        private const val VARIANT_NAME_STYLE = "yd_dict_variant_name"

        private val WORD_COLOR = JBColor(0x3333E8, 0xFFC66D)
        private val WORD_HOVER_COLOR = JBColor(0x762DFF, 0xDF7000)

    }

}