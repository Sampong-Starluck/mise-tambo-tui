package com.sampong.tambo.tui.features;

import java.util.regex.Pattern;

import dev.tamboui.widgets.syntax.Grammar;
import dev.tamboui.widgets.syntax.Grammar.Rule;
import dev.tamboui.widgets.syntax.RegexSyntaxHighlighter;
import dev.tamboui.widgets.syntax.SyntaxHighlighter;
import dev.tamboui.widgets.syntax.TokenType;

/**
 * A TOML grammar for the in-app config editor.
 * <p>
 * TamboUI 0.5.0 added syntax highlighting ({@code dev.tamboui.widgets.syntax}) and a
 * {@code TextAreaElement.highlighter(...)} hook, but the grammars it bundles cover
 * json/yaml/xml/properties and the mainstream programming languages — not TOML, which is the
 * one format this app actually edits ({@code mise.toml}, {@code .vfox.toml}, and mise's global
 * {@code config.toml}). So the grammar is defined here and handed to the editor as a custom
 * {@link SyntaxHighlighter}.
 * <p>
 * <strong>Every quantifier below is possessive ({@code *+} / {@code ++}).</strong> That is not a
 * style choice: TamboUI's own issue tracker records {@code RegexSyntaxHighlighter} blowing the
 * stack on unclosed string literals, because a rule written in the usual
 * {@code (?:[^"\\]|\\.)*} alternation-under-star shape makes Java's regex engine recurse once per
 * character. Possessive quantifiers never backtrack, so an unterminated string in a half-typed
 * config fails to match immediately instead of unwinding. {@link #MAX_LINE_LENGTH} is a second,
 * independent guard.
 */
public final class TomlSyntax {

    /** The language id the grammar registers under; passed alongside the highlighter. */
    public static final String LANGUAGE = "toml";

    /**
     * The guard TamboUI applies before matching. Note what it actually does: one line over the
     * limit switches highlighting off for the <em>entire document</em>, not just that line
     * (verified against 0.5.0). So this is set far above anything a hand-written config will
     * reach — a long {@code run = "…"} task or a generated path list must not silently
     * decolour the whole file.
     * <p>
     * It is only a backstop. The real protection against the pathological input is the
     * possessive quantifiers in {@link #grammar()}; an unterminated 40k-character
     * escape-heavy string was verified to scan without error on a 512 KB stack.
     */
    private static final int MAX_LINE_LENGTH = 32_768;

    /** Rules are matched in declaration order at each position, so earlier rules win. */
    private static final SyntaxHighlighter HIGHLIGHTER = RegexSyntaxHighlighter.builder()
            .add(grammar())
            .maxLineLength(MAX_LINE_LENGTH)
            .build();

    private TomlSyntax() {
    }

    /** The shared, immutable highlighter — building it once avoids recompiling patterns per frame. */
    public static SyntaxHighlighter highlighter() {
        return HIGHLIGHTER;
    }

    private static Grammar grammar() {
        return Grammar.builder(LANGUAGE)
                .alias("tml")

                // Multi-line strings first: they are the only construct allowed to span lines,
                // and their delimiters start with the same quote characters the single-line
                // string rules below match, so those would otherwise claim the opening """.
                .rule(Rule.multiline(TokenType.STRING, "\"\"\"", "\"\"\""))
                .rule(Rule.multiline(TokenType.STRING, "'''", "'''"))

                // Single-line strings before comments: a '#' inside quotes is content, not a
                // comment, and letting the string rule consume the whole literal first is what
                // makes that true.
                .rule(Rule.pattern(TokenType.STRING, Pattern.compile("\"(?:[^\"\\\\\\n]++|\\\\.)*+\"")))
                .rule(Rule.pattern(TokenType.STRING, Pattern.compile("'[^'\\n]*+'")))

                // Anything from an unquoted '#' to end of line.
                .rule(Rule.pattern(TokenType.COMMENT, Pattern.compile("#[^\\n]*+")))

                // Table headers: [tool] and [[array.of.tables]]. Line-anchored on purpose — an
                // unanchored rule would also swallow array values such as `paths = [1, 2]`,
                // since those open with the very same bracket.
                .rule(Rule.linePattern(TokenType.TYPE,
                        Pattern.compile("[ \\t]*+\\[\\[?[^\\]\\n]*+\\]\\]?")))

                // Keys, recognised by the '=' that must follow. A lookahead rather than a
                // line-anchored rule so inline tables ({ version = "21" }) highlight too.
                .rule(Rule.pattern(TokenType.ATTRIBUTE,
                        Pattern.compile("[A-Za-z0-9_.\\-]++(?=[ \\t]*+=)")))

                .rule(Rule.pattern(TokenType.CONSTANT, Pattern.compile("\\b(?:true|false)\\b")))

                // Dates before numbers: otherwise 2026-09-21 is read as the integer 2026
                // followed by two negative numbers.
                .rule(Rule.pattern(TokenType.NUMBER, Pattern.compile(
                        "\\d{4}-\\d{2}-\\d{2}(?:[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d++)?"
                                + "(?:[Zz]|[+-]\\d{2}:\\d{2})?)?")))
                .rule(Rule.pattern(TokenType.NUMBER, Pattern.compile(
                        "[+-]?(?:0x[0-9A-Fa-f_]++|0o[0-7_]++|0b[01_]++|inf|nan"
                                + "|\\d[\\d_]*+(?:\\.[\\d_]++)?(?:[eE][+-]?\\d++)?)")))

                .rule(Rule.literal(TokenType.OPERATOR, "="))
                .rule(Rule.pattern(TokenType.PUNCTUATION, Pattern.compile("[\\[\\]{},.]")))
                .build();
    }
}
