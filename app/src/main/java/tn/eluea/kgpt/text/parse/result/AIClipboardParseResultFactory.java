/*
 * Added for customizable AI Clipboard trigger.
 */
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class AIClipboardParseResultFactory implements ParseResultFactory {
    @Override
    public ParseResult getParseResult(List<String> groups, int indexStart, int indexEnd) {
        return new AIClipboardParseResult(groups, indexStart, indexEnd);
    }
}
