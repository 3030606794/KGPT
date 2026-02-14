/*
 * Added for customizable Quick Jump menu trigger.
 */
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class QuickJumpMenuParseResultFactory implements ParseResultFactory {
    @Override
    public ParseResult getParseResult(List<String> groups, int indexStart, int indexEnd) {
        return new QuickJumpMenuParseResult(groups, indexStart, indexEnd);
    }
}
