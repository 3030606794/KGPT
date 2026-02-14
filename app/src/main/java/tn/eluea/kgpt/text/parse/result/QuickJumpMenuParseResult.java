/*
 * Added for customizable Quick Jump menu trigger.
 */
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class QuickJumpMenuParseResult extends ParseResult {
    protected QuickJumpMenuParseResult(List<String> groups, int indexStart, int indexEnd) {
        super(groups, indexStart, indexEnd);
    }
}
