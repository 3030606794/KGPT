/*
 * Added for customizable model switch trigger.
 */
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class ModelSwitchParseResultFactory implements ParseResultFactory {
    @Override
    public ParseResult getParseResult(List<String> groups, int indexStart, int indexEnd) {
        return new ModelSwitchParseResult(groups, indexStart, indexEnd);
    }
}
