package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class QuickJumpParseResult extends ParseResult {
    public final String name;
    public final String trigger;
    public final String query;
    public final String url;

    public QuickJumpParseResult(List<String> groups, int indexStart, int indexEnd,
                                String name, String trigger, String query, String url) {
        super(groups, indexStart, indexEnd);
        this.name = name;
        this.trigger = trigger;
        this.query = query;
        this.url = url;
    }
}
