import java.io.*;
import java.util.*;

public final class HuffmanCompress {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java HuffmanCompress <input file> <output file>");
            System.exit(1);
            return;
        }

        File inputFile = new File(args[0]);
        File outputFile = new File(args[1]);

        FrequencyTable freqs = new FrequencyTable(new int[257]);
        try (InputStream input = new BufferedInputStream(new FileInputStream(inputFile))) {
            while (true) {
                int b = input.read();
                if (b == -1)
                    break;
                freqs.increment(b);
            }
        }
        freqs.increment(256);

        CodeTree code = freqs.buildCodeTree();
        CanonicalCode canonCode = new CanonicalCode(code, freqs.getSymbolLimit());
        code = canonCode.toCodeTree();

        long inputSize = inputFile.length();

        try (InputStream in = new BufferedInputStream(new FileInputStream(inputFile));
             BitOutputStream out = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)))) {

            writeCodeLengthTable(out, canonCode);
            compress(code, in, out);
        }

        long outputSize = outputFile.length();
        System.out.println("Input file size: " + inputSize + " bytes");
        System.out.println("Output file size: " + outputSize + " bytes");
        double ratio = (inputSize == 0) ? 0 : ((double) outputSize / inputSize) * 100.0;
        System.out.printf("Compression ratio: %.2f%%\n", ratio);
    }

    static void writeCodeLengthTable(BitOutputStream out, CanonicalCode canonCode) throws IOException {
        for (int i = 0; i < canonCode.getSymbolLimit(); i++) {
            int val = canonCode.getCodeLength(i);
            if (val >= 256)
                throw new RuntimeException("The code for a symbol is too long");

            for (int j = 7; j >= 0; j--)
                out.write((val >>> j) & 1);
        }
    }

    static void compress(CodeTree code, InputStream in, BitOutputStream out) throws IOException {
        HuffmanEncoder enc = new HuffmanEncoder(out);
        enc.codeTree = code;
        while (true) {
            int b = in.read();
            if (b == -1)
                break;
            enc.write(b);
        }
        enc.write(256);
    }
}

class FrequencyTable {
    private int[] frequencies;

    public FrequencyTable(int[] freqs) {
        if (freqs == null)
            throw new NullPointerException("Null array");
        frequencies = freqs.clone();
    }

    public int getSymbolLimit() {
        return frequencies.length;
    }

    public int get(int symbol) {
        return frequencies[symbol];
    }

    public void increment(int symbol) {
        frequencies[symbol]++;
    }

    public CodeTree buildCodeTree() {
        Queue<NodeWithFrequency> pqueue = new PriorityQueue<>();

        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] > 0)
                pqueue.add(new NodeWithFrequency(new Leaf(i), i, frequencies[i]));
        }

        for (int i = 0; i < frequencies.length && pqueue.size() < 2; i++) {
            if (frequencies[i] == 0)
                pqueue.add(new NodeWithFrequency(new Leaf(i), i, 0));
        }

        if (pqueue.size() < 2)
            throw new AssertionError();

        while (pqueue.size() > 1) {
            NodeWithFrequency x = pqueue.remove();
            NodeWithFrequency y = pqueue.remove();
            pqueue.add(new NodeWithFrequency(
                    new InternalNode(x.node, y.node),
                    Math.min(x.lowestSymbol, y.lowestSymbol),
                    x.frequency + y.frequency
            ));
        }

        return new CodeTree((InternalNode) pqueue.remove().node, frequencies.length);
    }

    private static class NodeWithFrequency implements Comparable<NodeWithFrequency> {
        public final Node node;
        public final int lowestSymbol;
        public final int frequency;

        public NodeWithFrequency(Node nd, int low, int freq) {
            node = nd;
            lowestSymbol = low;
            frequency = freq;
        }

        public int compareTo(NodeWithFrequency other) {
            if (this.frequency != other.frequency)
                return this.frequency - other.frequency;
            return this.lowestSymbol - other.lowestSymbol;
        }
    }
}

abstract class Node {
}

final class InternalNode extends Node {
    public final Node leftChild;
    public final Node rightChild;

    public InternalNode(Node left, Node right) {
        leftChild = Objects.requireNonNull(left);
        rightChild = Objects.requireNonNull(right);
    }
}

final class Leaf extends Node {
    private final int symbol;

    public Leaf(int sym) {
        if (sym < 0)
            throw new IllegalArgumentException("Illegal symbol value");
        symbol = sym;
    }

    public int symbol() {
        return symbol;
    }
}

class CodeTree {
    public final InternalNode root;
    private List<List<Integer>> codes;

    public CodeTree(InternalNode root, int symbolLimit) {
        this.root = Objects.requireNonNull(root);
        if (symbolLimit < 2)
            throw new IllegalArgumentException("At least 2 symbols needed");

        codes = new ArrayList<>(Collections.nCopies(symbolLimit, null));
        buildCodeList(root, new ArrayList<>());
    }

    private void buildCodeList(Node node, List<Integer> prefix) {
        if (node instanceof InternalNode) {
            InternalNode internal = (InternalNode) node;
            prefix.add(0);
            buildCodeList(internal.leftChild, prefix);
            prefix.remove(prefix.size() - 1);

            prefix.add(1);
            buildCodeList(internal.rightChild, prefix);
            prefix.remove(prefix.size() - 1);
        } else if (node instanceof Leaf) {
            Leaf leaf = (Leaf) node;
            codes.set(leaf.symbol(), new ArrayList<>(prefix));
        } else {
            throw new AssertionError("Illegal node type");
        }
    }

    public List<Integer> getCode(int symbol) {
        if (symbol < 0 || symbol >= codes.size() || codes.get(symbol) == null)
            throw new IllegalArgumentException("Invalid or missing code for symbol");
        return codes.get(symbol);
    }
}

class CanonicalCode {
    private int[] codeLengths;

    public CanonicalCode(CodeTree tree, int symbolLimit) {
        codeLengths = new int[symbolLimit];
        buildCodeLengths(tree.root, 0);
    }

    private void buildCodeLengths(Node node, int depth) {
        if (node instanceof InternalNode) {
            InternalNode internal = (InternalNode) node;
            buildCodeLengths(internal.leftChild, depth + 1);
            buildCodeLengths(internal.rightChild, depth + 1);
        } else if (node instanceof Leaf) {
            Leaf leaf = (Leaf) node;
            codeLengths[leaf.symbol()] = depth;
        } else {
            throw new AssertionError("Illegal node type");
        }
    }

    public int getSymbolLimit() {
        return codeLengths.length;
    }

    public int getCodeLength(int symbol) {
        return codeLengths[symbol];
    }

    public CodeTree toCodeTree() {
        List<Node> nodes = new ArrayList<>();
        for (int i = max(codeLengths); i >= 0; i--) {
            List<Node> newNodes = new ArrayList<>();
            if (i > 0) {
                for (int j = 0; j < codeLengths.length; j++) {
                    if (codeLengths[j] == i)
                        newNodes.add(new Leaf(j));
                }
            }
            for (int j = 0; j < nodes.size(); j += 2)
                newNodes.add(new InternalNode(nodes.get(j), nodes.get(j + 1)));
            nodes = newNodes;
        }
        if (nodes.size() != 1)
            throw new AssertionError("Violation of canonical code invariants");
        return new CodeTree((InternalNode) nodes.get(0), codeLengths.length);
    }

    private static int max(int[] array) {
        int result = array[0];
        for (int x : array)
            result = Math.max(result, x);
        return result;
    }
}

class HuffmanEncoder {
    private BitOutputStream output;
    public CodeTree codeTree;

    public HuffmanEncoder(BitOutputStream out) {
        output = Objects.requireNonNull(out);
    }

    public void write(int symbol) throws IOException {
        if (codeTree == null)
            throw new NullPointerException("Code tree is null");
        List<Integer> bits = codeTree.getCode(symbol);
        for (int b : bits)
            output.write(b);
    }
}

class BitOutputStream implements Closeable {
    private OutputStream output;
    private int currentByte;
    private int numBitsFilled;

    public BitOutputStream(OutputStream out) {
        output = Objects.requireNonNull(out);
        currentByte = 0;
        numBitsFilled = 0;
    }

    public void write(int b) throws IOException {
        if (!(b == 0 || b == 1))
            throw new IllegalArgumentException("Argument must be 0 or 1");
        currentByte = (currentByte << 1) | b;
        numBitsFilled++;
        if (numBitsFilled == 8) {
            output.write(currentByte);
            numBitsFilled = 0;
        }
    }

    public void close() throws IOException {
        while (numBitsFilled != 0)
            write(0);
        output.close();
    }
}
