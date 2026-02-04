/*
 * Added for customizable model switch trigger.
 */
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class ModelSwitchParseResult extends ParseResult {
    protected ModelSwitchParseResult(List<String> groups, int indexStart, int indexEnd) {
        super(groups, indexStart, indexEnd);
    }
}
