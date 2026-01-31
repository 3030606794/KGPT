/*
 * Added for customizable AI Clipboard trigger.
 */
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class AIClipboardParseResult extends ParseResult {
    protected AIClipboardParseResult(List<String> groups, int indexStart, int indexEnd) {
        super(groups, indexStart, indexEnd);
    }
}
