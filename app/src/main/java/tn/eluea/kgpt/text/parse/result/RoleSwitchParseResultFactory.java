/*
 * Added for customizable role switch trigger.
 */
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class RoleSwitchParseResultFactory implements ParseResultFactory {
    @Override
    public ParseResult getParseResult(List<String> groups, int indexStart, int indexEnd) {
        return new RoleSwitchParseResult(groups, indexStart, indexEnd);
    }
}
