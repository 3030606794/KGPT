/*
 * Added for customizable role switch trigger.
 */
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class RoleSwitchParseResult extends ParseResult {
    protected RoleSwitchParseResult(List<String> groups, int indexStart, int indexEnd) {
        super(groups, indexStart, indexEnd);
    }
}
