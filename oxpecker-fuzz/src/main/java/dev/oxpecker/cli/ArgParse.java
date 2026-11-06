package dev.oxpecker.cli;

import dev.oxpecker.util.Py;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of Python's argparse the CLI uses, with its behaviour and its output: subcommands,
 * {@code store}/{@code store_true}/{@code append} options with int, float and string values,
 * {@code nargs="+"} positionals, choices, required options, parser-level defaults, unique-prefix
 * abbreviations of long options, {@code --opt=value} and {@code -nVALUE}, {@code --}, negative
 * numbers as values, and argparse's help and error formatting (HelpFormatter, with
 * RawDescriptionHelpFormatter for the top-level description; textwrap for help texts). Errors print
 * the usage and {@code prog: error: message} to stderr and exit 2; {@code -h} prints the help and
 * exits 0 ({@link Exit}).
 */
final class ArgParse {
    private ArgParse() {}

    /** sys.exit from inside the parser: the code to return. */
    static final class Exit extends RuntimeException {
        final int code;

        Exit(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    enum Action { STORE, STORE_TRUE, APPEND, HELP, PARSERS }

    /** An argparse Action. */
    static final class Arg {
        final List<String> flags;
        String dest;
        Action action = Action.STORE;
        String type;               // null (str), "int" or "float"
        Object dflt;
        boolean required;
        String help;
        String metavar;
        List<String> choices;
        String nargs;              // null (one value), "+", or "..." (subcommands)
        Parser owner;

        Arg(List<String> flags) {
            this.flags = flags;
        }

        boolean optional() { return !flags.isEmpty(); }

        Arg help(String v) { help = v; return this; }
        Arg type(String v) { type = v; return this; }
        Arg dflt(Object v) { dflt = v; return this; }
        Arg required() { required = true; return this; }
        Arg metavar(String v) { metavar = v; return this; }
        Arg choices(String... v) { choices = List.of(v); return this; }
        Arg nargs(String v) { nargs = v; return this; }
        Arg storeTrue() { action = Action.STORE_TRUE; dflt = false; return this; }
        Arg append() { action = Action.APPEND; return this; }

        /** argparse's _get_action_name. */
        String name() {
            if (optional()) return String.join("/", flags);
            if (metavar != null) return metavar;
            if (dest != null) return dest;
            return "{" + String.join(",", choices) + "}";
        }
    }

    /** The subparsers action: a positional whose choices are parsers. */
    static final class Subparsers {
        final Arg action;
        final Map<String, Parser> parsers = new LinkedHashMap<>();
        final Map<String, String> helps = new LinkedHashMap<>();
        private final Parser parent;

        Subparsers(Parser parent, Arg action) {
            this.parent = parent;
            this.action = action;
        }

        Parser add(String name, String help) {
            Parser p = new Parser(parent.prog + " " + name, null);
            p.columns = parent.columns;
            parsers.put(name, p);
            helps.put(name, help);
            action.choices = new ArrayList<>(parsers.keySet());
            return p;
        }
    }

    /** The parsed values: argparse's Namespace. */
    static final class Ns {
        final Map<String, Object> values = new LinkedHashMap<>();

        boolean has(String k) { return values.containsKey(k); }
        Object get(String k) { return values.get(k); }
        String str(String k) { return (String) values.get(k); }
        Integer integer(String k) { return (Integer) values.get(k); }
        Double dbl(String k) { return (Double) values.get(k); }
        boolean bool(String k) { return Boolean.TRUE.equals(values.get(k)); }

        @SuppressWarnings("unchecked")
        List<String> list(String k) { return (List<String>) values.get(k); }

        @Override public String toString() { return "Namespace" + values; }
    }

    private static final Pattern NEGATIVE_NUMBER = Pattern.compile("^-\\d+$|^-\\d*\\.\\d+$");

    static final class Parser {
        final String prog;
        final String description;
        final List<Arg> actions = new ArrayList<>();
        final Map<String, Arg> optionStrings = new LinkedHashMap<>();
        final Map<String, Object> defaults = new LinkedHashMap<>();
        Subparsers subparsers;
        /** Terminal width (shutil.get_terminal_size: COLUMNS, else 80). */
        int columns;

        Parser(String prog, String description) {
            this.prog = prog;
            this.description = description;
            this.columns = terminalColumns();
            Arg h = add("-h", "--help").help("show this help message and exit");
            h.action = Action.HELP;
        }

        Arg add(String... names) {
            Arg a;
            if (names[0].startsWith("-")) {
                a = new Arg(List.of(names));
                String longest = null;
                for (String n : names) if (n.startsWith("--")) { longest = n; break; }
                String d = longest != null ? longest.substring(2) : names[0].substring(1);
                a.dest = d.replace('-', '_');
                for (String n : names) optionStrings.put(n, a);
            } else {
                a = new Arg(List.of());
                a.dest = names[0];
            }
            a.owner = this;
            actions.add(a);
            return a;
        }

        Subparsers addSubparsers(String dest, boolean required) {
            Arg a = new Arg(List.of());
            a.dest = dest;
            a.action = Action.PARSERS;
            a.nargs = "...";
            a.required = required;
            a.choices = new ArrayList<>();
            a.owner = this;
            actions.add(a);
            subparsers = new Subparsers(this, a);
            return subparsers;
        }

        /** parser.set_defaults: also the default of an action with that dest. */
        Parser setDefaults(Object... kv) {
            for (int i = 0; i < kv.length; i += 2) {
                String k = (String) kv[i];
                defaults.put(k, kv[i + 1]);
                for (Arg a : actions) if (k.equals(a.dest)) a.dflt = kv[i + 1];
            }
            return this;
        }

        // ------------------------------------------------------------------------------ parsing

        /** parse_args: errors go to {@code err} and throw Exit(2); help to {@code out}, Exit(0). */
        Ns parse(List<String> args, PrintStream out, PrintStream err) {
            Ns ns = new Ns();
            List<String> extras = new ArrayList<>();
            parseKnown(args, ns, extras, out, err);
            if (!extras.isEmpty()) error("unrecognized arguments: " + String.join(" ", extras), err);
            return ns;
        }

        private boolean looksOptional(String s) {
            if (s.isEmpty() || s.charAt(0) != '-' || s.equals("-")) return false;
            if (optionStrings.containsKey(s)) return true;
            if (s.length() == 1) return false;
            if (s.contains("=")) {
                String opt = s.substring(0, s.indexOf('='));
                if (optionStrings.containsKey(opt)) return true;
            }
            if (NEGATIVE_NUMBER.matcher(s).matches()) return false;     // no option looks like a negative number
            if (s.contains(" ")) return false;
            return true;
        }

        /** (action, explicit argument) for an option string, or null if unknown. */
        private Object[] resolve(String s, PrintStream err) {
            if (optionStrings.containsKey(s)) return new Object[] {optionStrings.get(s), null};
            if (s.contains("=")) {
                String opt = s.substring(0, s.indexOf('='));
                if (optionStrings.containsKey(opt)) return new Object[] {optionStrings.get(opt), s.substring(s.indexOf('=') + 1)};
            }
            // abbreviations of long options (allow_abbrev)
            if (s.startsWith("--")) {
                String opt = s.contains("=") ? s.substring(0, s.indexOf('=')) : s;
                String explicit = s.contains("=") ? s.substring(s.indexOf('=') + 1) : null;
                List<String> matches = new ArrayList<>();
                for (String o : optionStrings.keySet()) if (o.startsWith("--") && o.startsWith(opt)) matches.add(o);
                if (matches.size() > 1) error("ambiguous option: " + s + " could match " + String.join(", ", matches), err);
                if (matches.size() == 1) return new Object[] {optionStrings.get(matches.get(0)), explicit};
            } else if (s.length() > 2 && s.charAt(1) != '-') {
                String opt = s.substring(0, 2);
                if (optionStrings.containsKey(opt)) return new Object[] {optionStrings.get(opt), s.substring(2)};
            }
            return null;
        }

        private void parseKnown(List<String> args, Ns ns, List<String> extras, PrintStream out, PrintStream err) {
            for (Arg a : actions) {
                if (a.action == Action.HELP || ns.has(a.dest)) continue;
                ns.values.put(a.dest, a.dflt);
            }
            for (Map.Entry<String, Object> e : defaults.entrySet()) if (!ns.has(e.getKey())) ns.values.put(e.getKey(), e.getValue());

            List<Arg> positionals = actions.stream().filter(a -> !a.optional()).toList();
            int nextPositional = 0;
            List<Arg> seen = new ArrayList<>();
            int i = 0, n = args.size();
            while (i < n) {
                String s = args.get(i);
                if (s.equals("--")) {
                    List<String> rest = args.subList(i + 1, n);
                    if (nextPositional >= positionals.size()) {
                        extras.addAll(args.subList(i, n));      // nothing takes them, '--' included
                        break;
                    }
                    nextPositional = consumePositionals(positionals, nextPositional, rest, rest, ns, extras, seen, out, err);
                    break;
                }
                if (looksOptional(s)) {
                    Object[] r = resolve(s, err);
                    if (r == null) {
                        extras.add(s);
                        i++;
                        continue;
                    }
                    Arg a = (Arg) r[0];
                    String explicit = (String) r[1];
                    i++;
                    while (true) {
                        switch (a.action) {
                            case HELP -> {
                                out.print(formatHelp());
                                out.flush();
                                throw new Exit(0);
                            }
                            case STORE_TRUE -> {
                                boolean attachedShort = !s.startsWith("--") && s.length() > 2 && s.charAt(2) != '=';
                                if (attachedShort && explicit != null && !explicit.isEmpty()) {
                                    // -vq: the rest is another short flag (and maybe its value)
                                    Arg next = optionStrings.get("-" + explicit.charAt(0));
                                    if (next == null) error("argument " + a.name() + ": ignored explicit argument " + Py.repr(explicit), err);
                                    ns.values.put(a.dest, true);
                                    seen.add(a);
                                    a = next;
                                    explicit = explicit.length() > 1 ? explicit.substring(1) : null;
                                    continue;
                                }
                                if (explicit != null) error("argument " + a.name() + ": ignored explicit argument " + Py.repr(explicit), err);
                                ns.values.put(a.dest, true);
                            }
                            default -> {
                                String value;
                                if (explicit != null) {
                                    value = explicit;
                                } else if (i < n && !args.get(i).equals("--") && !looksOptional(args.get(i))) {
                                    value = args.get(i++);
                                } else {
                                    error("argument " + a.name() + ": expected one argument", err);
                                    return;
                                }
                                Object v = convert(a, value, err);
                                if (a.action == Action.APPEND) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> l = ns.get(a.dest) instanceof List<?> old ? new ArrayList<>((List<Object>) old) : new ArrayList<>();
                                    l.add(v);
                                    ns.values.put(a.dest, l);
                                } else {
                                    ns.values.put(a.dest, v);
                                }
                            }
                        }
                        seen.add(a);
                        break;
                    }
                    continue;
                }
                int j = i;
                while (j < n && !args.get(j).equals("--") && !looksOptional(args.get(j))) j++;
                List<String> run = args.subList(i, j);
                boolean parsers = nextPositional < positionals.size() && positionals.get(nextPositional).action == Action.PARSERS;
                nextPositional = consumePositionals(positionals, nextPositional, run, args.subList(i, n), ns, extras, seen, out, err);
                if (parsers) break;       // the subcommand took everything after it
                i = j;
            }
            List<String> missing = new ArrayList<>();
            for (Arg a : actions) {
                // a positional is required unless it is the subcommand and that is not
                boolean required = a.optional() || a.action == Action.PARSERS ? a.required : true;
                if (required && !seen.contains(a)) missing.add(a.name());
            }
            if (!missing.isEmpty()) error("the following arguments are required: " + String.join(", ", missing), err);
        }

        /** Fills positionals from a run of positional strings; returns the next unfilled one. A
         * subcommand takes {@code rest} (everything from its name on). */
        private int consumePositionals(List<Arg> positionals, int next, List<String> run, List<String> rest, Ns ns,
                                       List<String> extras, List<Arg> seen, PrintStream out, PrintStream err) {
            int k = 0;
            while (k < run.size() && next < positionals.size()) {
                Arg a = positionals.get(next++);
                if (a.action == Action.PARSERS) {
                    String name = run.get(k);
                    checkChoice(a, name, err);
                    seen.add(a);
                    ns.values.put(a.dest, name);
                    Parser sub = subparsers.parsers.get(name);
                    Ns subNs = new Ns();
                    sub.parseKnown(new ArrayList<>(rest.subList(1, rest.size())), subNs, extras, out, err);
                    ns.values.putAll(subNs.values);
                    return next;
                }
                if ("+".equals(a.nargs)) {
                    List<Object> vs = new ArrayList<>();
                    for (; k < run.size(); k++) vs.add(convert(a, run.get(k), err));
                    ns.values.put(a.dest, vs);
                } else {
                    ns.values.put(a.dest, convert(a, run.get(k++), err));
                }
                seen.add(a);
            }
            for (; k < run.size(); k++) extras.add(run.get(k));
            return next;
        }

        private void checkChoice(Arg a, String value, PrintStream err) {
            if (a.choices != null && !a.choices.contains(value)) {
                List<String> quoted = a.choices.stream().map(Py::repr).toList();
                error("argument " + a.name() + ": invalid choice: " + Py.repr(value) + " (choose from " + String.join(", ", quoted) + ")", err);
            }
        }

        private Object convert(Arg a, String value, PrintStream err) {
            Object v = value;
            if ("int".equals(a.type)) {
                try {
                    v = Integer.parseInt(value.strip().replace("_", ""));
                } catch (NumberFormatException e) {
                    error("argument " + a.name() + ": invalid int value: " + Py.repr(value), err);
                }
            } else if ("float".equals(a.type)) {
                try {
                    String t = value.strip().toLowerCase();
                    v = switch (t) {
                        case "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY;
                        case "-inf", "-infinity" -> Double.NEGATIVE_INFINITY;
                        case "nan", "+nan", "-nan" -> Double.NaN;
                        default -> {
                            if (t.endsWith("d") || t.endsWith("f")) throw new NumberFormatException();
                            yield Double.parseDouble(t.replace("_", ""));
                        }
                    };
                } catch (NumberFormatException e) {
                    error("argument " + a.name() + ": invalid float value: " + Py.repr(value), err);
                }
            }
            checkChoice(a, value, err);
            return v;
        }

        void error(String message, PrintStream err) {
            err.print(formatUsage());
            err.print(prog + ": error: " + message + "\n");
            err.flush();
            throw new Exit(2);
        }

        // ------------------------------------------------------------------------------ help

        String formatUsage() {
            return "usage: " + usageBody("usage: ") + "\n";
        }

        private String metavarOf(Arg a, String dflt) {
            if (a.metavar != null) return a.metavar;
            if (a.choices != null) return "{" + String.join(",", a.choices) + "}";
            return dflt;
        }

        private String formatArgs(Arg a, String dflt) {
            String m = metavarOf(a, dflt);
            if ("+".equals(a.nargs)) return m + " [" + m + " ...]";
            if ("...".equals(a.nargs)) return m + " ...";
            return m;
        }

        private String usagePart(Arg a) {
            if (!a.optional()) return formatArgs(a, a.dest);
            String part = a.flags.get(0);
            if (a.action == Action.STORE || a.action == Action.APPEND) part += " " + formatArgs(a, a.dest.toUpperCase());
            return a.required ? part : "[" + part + "]";
        }

        private String usageBody(String prefix) {
            List<String> opt = new ArrayList<>(), pos = new ArrayList<>();
            for (Arg a : actions) (a.optional() ? opt : pos).add(usagePart(a));
            List<String> all = new ArrayList<>(opt);
            all.addAll(pos);
            String usage = all.isEmpty() ? prog : prog + " " + String.join(" ", all);
            int textWidth = width();
            if (prefix.length() + usage.length() <= textWidth) return usage;
            List<String> lines;
            if (prefix.length() + prog.length() <= 0.75 * textWidth) {
                String indent = " ".repeat(prefix.length() + prog.length() + 1);
                if (!opt.isEmpty()) {
                    List<String> first = new ArrayList<>(List.of(prog));
                    first.addAll(opt);
                    lines = new ArrayList<>(wrapParts(first, indent, prefix, textWidth));
                    lines.addAll(wrapParts(pos, indent, null, textWidth));
                } else if (!pos.isEmpty()) {
                    List<String> first = new ArrayList<>(List.of(prog));
                    first.addAll(pos);
                    lines = wrapParts(first, indent, prefix, textWidth);
                } else {
                    lines = List.of(prog);
                }
            } else {
                String indent = " ".repeat(prefix.length());
                List<String> parts = new ArrayList<>(opt);
                parts.addAll(pos);
                lines = new ArrayList<>(wrapParts(parts, indent, null, textWidth));
                if (lines.size() > 1) {
                    lines = new ArrayList<>(wrapParts(opt, indent, null, textWidth));
                    lines.addAll(wrapParts(pos, indent, null, textWidth));
                }
                lines.add(0, prog);
            }
            return String.join("\n", lines);
        }

        private static List<String> wrapParts(List<String> parts, String indent, String prefix, int textWidth) {
            List<String> lines = new ArrayList<>();
            List<String> line = new ArrayList<>();
            int lineLen = prefix != null ? prefix.length() - 1 : indent.length() - 1;
            for (String part : parts) {
                if (lineLen + 1 + part.length() > textWidth && !line.isEmpty()) {
                    lines.add(indent + String.join(" ", line));
                    line = new ArrayList<>();
                    lineLen = indent.length() - 1;
                }
                line.add(part);
                lineLen += part.length() + 1;
            }
            if (!line.isEmpty()) lines.add(indent + String.join(" ", line));
            if (prefix != null && !lines.isEmpty()) lines.set(0, lines.get(0).substring(indent.length()));
            return lines;
        }

        private int width() {
            return Math.max(columns - 2, 11);
        }

        private String invocation(Arg a) {
            if (!a.optional()) return metavarOf(a, a.dest);
            String flags = String.join(", ", a.flags);
            if (a.action == Action.STORE_TRUE || a.action == Action.HELP) return flags;
            return flags + " " + formatArgs(a, a.dest.toUpperCase());
        }

        /** One help item per subcommand, as argparse's _ChoicesPseudoAction. */
        private List<String[]> subactions(Arg a) {
            List<String[]> out = new ArrayList<>();
            if (a.action == Action.PARSERS) for (var e : subparsers.helps.entrySet()) out.add(new String[] {e.getKey(), e.getValue()});
            return out;
        }

        String formatHelp() {
            int width = width();
            int maxHelpPosition = Math.min(24, Math.max(width - 20, 4));
            int actionMaxLength = 0;
            for (Arg a : actions) {
                actionMaxLength = Math.max(actionMaxLength, invocation(a).length() + 2);
                for (String[] s : subactions(a)) actionMaxLength = Math.max(actionMaxLength, s[0].length() + 4);
            }
            int helpPosition = Math.min(actionMaxLength + 2, maxHelpPosition);
            StringBuilder sb = new StringBuilder();
            sb.append("usage: ").append(usageBody("usage: ")).append("\n\n");
            if (description != null) {
                // RawDescriptionHelpFormatter: kept as written
                sb.append(description).append("\n\n");
            }
            List<Arg> pos = actions.stream().filter(a -> !a.optional()).toList();
            List<Arg> opt = actions.stream().filter(Arg::optional).toList();
            for (var section : List.of(Map.entry("positional arguments", pos), Map.entry("options", opt))) {
                if (section.getValue().isEmpty()) continue;
                sb.append('\n').append(section.getKey()).append(":\n");
                for (Arg a : section.getValue()) {
                    formatAction(sb, invocation(a), a.help, 2, helpPosition, width);
                    for (String[] s : subactions(a)) formatAction(sb, s[0], s[1], 4, helpPosition, width);
                }
                sb.append('\n');
            }
            String help = sb.toString().replaceAll("\n\n\n+", "\n\n");
            return Py.strip(help, "\n") + "\n";
        }

        private static void formatAction(StringBuilder sb, String header, String help, int indent, int helpPosition, int width) {
            int helpWidth = Math.max(width - helpPosition, 11);
            int actionWidth = helpPosition - indent - 2;
            int indentFirst;
            if (help == null) {
                sb.append(" ".repeat(indent)).append(header).append('\n');
                return;
            } else if (header.length() <= actionWidth) {
                sb.append(" ".repeat(indent)).append(header).append(" ".repeat(actionWidth - header.length())).append("  ");
                indentFirst = 0;
            } else {
                sb.append(" ".repeat(indent)).append(header).append('\n');
                indentFirst = helpPosition;
            }
            if (!help.strip().isEmpty()) {
                List<String> lines = TextWrap.wrap(help.replaceAll("[ \\t\\n\\r\\x0b\\x0c]+", " ").strip(), helpWidth);
                sb.append(" ".repeat(indentFirst)).append(lines.get(0)).append('\n');
                for (String l : lines.subList(1, lines.size())) sb.append(" ".repeat(helpPosition)).append(l).append('\n');
            } else if (!header.endsWith("\n")) {
                sb.append('\n');
            }
        }
    }

    static int terminalColumns() {
        String c = System.getenv("COLUMNS");
        if (c != null) {
            try {
                int v = Integer.parseInt(c.strip());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 80;
    }

    /** textwrap.wrap with its defaults (break_long_words, break_on_hyphens, drop_whitespace). */
    static final class TextWrap {
        private TextWrap() {}

        private static final String WS = "[\\t\\n\\x0b\\x0c\\r ]";
        private static final String NWS = "[^\\t\\n\\x0b\\x0c\\r ]";
        private static final String LT = "[^\\d\\W]";
        private static final String WP = "[\\w!\"'&.,?]";
        private static final Pattern WORDSEP = Pattern.compile(
                "(" + WS + "+"
                + "|(?<=" + WP + ")-{2,}(?=\\w)"
                + "|" + NWS + "+?(?:"
                + "-(?:(?<=" + LT + "{2}-)|(?<=" + LT + "-" + LT + "-))(?=" + LT + "-?" + LT + ")"
                + "|(?=" + WS + "|\\z)"
                + "|(?<=" + WP + ")(?=-{2,}\\w)"
                + "))", Pattern.UNICODE_CHARACTER_CLASS);

        static List<String> chunks(String text) {
            List<String> out = new ArrayList<>();
            Matcher m = WORDSEP.matcher(text);
            int last = 0;
            while (m.find()) {
                if (m.start() > last) out.add(text.substring(last, m.start()));
                if (!m.group().isEmpty()) out.add(m.group());
                last = m.end();
            }
            if (last < text.length()) out.add(text.substring(last));
            return out;
        }

        static List<String> wrap(String text, int width) {
            List<String> chunks = new ArrayList<>(chunks(text));
            java.util.Collections.reverse(chunks);
            List<String> lines = new ArrayList<>();
            while (!chunks.isEmpty()) {
                List<String> cur = new ArrayList<>();
                int curLen = 0;
                if (last(chunks).strip().isEmpty() && !lines.isEmpty()) chunks.remove(chunks.size() - 1);
                while (!chunks.isEmpty()) {
                    int l = last(chunks).length();
                    if (curLen + l <= width) {
                        cur.add(chunks.remove(chunks.size() - 1));
                        curLen += l;
                    } else {
                        break;
                    }
                }
                if (!chunks.isEmpty() && last(chunks).length() > width) {
                    int spaceLeft = Math.max(width - curLen, 1);
                    String chunk = last(chunks);
                    int end = spaceLeft;
                    if (chunk.length() > spaceLeft) {
                        int hyphen = chunk.lastIndexOf('-', spaceLeft - 1);
                        if (hyphen > 0 && chunk.substring(0, hyphen).chars().anyMatch(c -> c != '-')) end = hyphen + 1;
                    }
                    cur.add(chunk.substring(0, end));
                    chunks.set(chunks.size() - 1, chunk.substring(end));
                    curLen += end;
                }
                if (!cur.isEmpty() && last(cur).strip().isEmpty()) cur.remove(cur.size() - 1);
                if (!cur.isEmpty()) lines.add(String.join("", cur));
            }
            return lines;
        }

        private static String last(List<String> l) {
            return l.get(l.size() - 1);
        }
    }
}
