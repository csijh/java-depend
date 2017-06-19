import java.io.*;
import java.util.*;

/* Find dependencies between the Java classes in a directory. Present the
results in reverse dependency order, emphasizing cyclic groups. Assumes the
Java 8 class file format. Type java Depend [directory]. */

class Depend {
    private File folder;
    private List<Node> nodes;
    private List<Set<Node>> groups;

    // A Node represents the info associated with a class
    class Node {
        String fileName, className, classPath;
        List<String> refs;
        List<Node> out, in;
        boolean visited;
        Set<Node> group;
        public String toString() { return className; }
    }

    public static void main(String[] args) throws Exception {
        Depend program = new Depend();
        if (args.length >= 1 && (args.length > 1 || args[0].charAt(0) == '-')) {
            System.err.println("Use:  java Depend [directory]");
            System.exit(0);
        }
        program.folder = new File(args.length > 0 ? args[0] : ".");
        program.run();
    }

    // Find dependencies in the directory of classes.
    private void run() throws Exception {
        findClasses();
        for (Node node : nodes) readClass(node);
        buildRefs();
        reverseRefs();
        findGroups();
        print();
    }

    // Find the classes in the directory, excluding inner classes.
    private void findClasses() {
        nodes = new ArrayList<Node>();
        for (String name: folder.list()) {
            if (! name.endsWith(".class")) continue;
            if (name.contains("$")) continue;
            Node node = new Node();
            node.fileName = name;
            node.className = name.substring(0, name.length()-6);
            nodes.add(node);
        }
    }

    // Define the codes used in class files for types of constant pool entry.
    private static final int
	    UTF8 = 1, INTEGER = 3, FLOAT = 4, LONG = 5, DOUBLE = 6, CLASS = 7,
	    STRING = 8, FIELDREF = 9, METHODREF = 10, INTERFACE_METHODREF = 11,
	    NAME_AND_TYPE = 12, METHOD_HANDLE = 15, METHOD_TYPE = 16,
        INVOKE_DYNAMIC = 18;

    // Read a class file, finding the class path, and extracting the references
    // to other classes from the constant pool.
    void readClass(Node node) throws Exception {
        InputStream is = new FileInputStream(new File(folder, node.fileName));
        DataInputStream in = new DataInputStream(is);
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int nConstants = in.readUnsignedShort();
        String[] names = new String[nConstants];
        int[] classes = new int[nConstants];
        for (int i = 1; i < nConstants; i++) {
            int type = in.readUnsignedByte();
            switch (type) {
            case UTF8: names[i] = in.readUTF(); break;
            case CLASS: classes[i] = in.readUnsignedShort(); break;
            case FIELDREF: case METHODREF: case INTERFACE_METHODREF:
            case NAME_AND_TYPE: case INVOKE_DYNAMIC:
                in.readUnsignedShort();
                in.readUnsignedShort();
                break;
            case STRING: case METHOD_TYPE: in.readUnsignedShort(); break;
            case INTEGER: in.readInt(); break;
            case FLOAT: in.readFloat(); break;
            case LONG: in.readLong(); i++; break;
            case DOUBLE: in.readDouble(); i++; break;
            case METHOD_HANDLE:
                in.readUnsignedByte();
                in.readUnsignedShort();
                break;
            }
        }
        in.readUnsignedShort();
        int thisClass = classes[in.readUnsignedShort()];
        node.classPath = names[thisClass];
        in.close();
        node.refs = new ArrayList<String>();
        for (int i=0; i<classes.length; i++) {
            if (classes[i] == 0) continue;
            String name = names[classes[i]];
            if (name.contains("$")) continue;
            if (name.equals(node.classPath)) continue;
            if (node.refs.contains(name)) continue;
            node.refs.add(name);
        }
    }

    // Build a map from classPath to node.  Use it to convert the refs into
    // direct node pointers.  Discard refs to classes outside the folder.
    void buildRefs() {
        Map<String,Node> map = new HashMap<String,Node>();
        for (Node node : nodes) map.put(node.classPath, node);
        for (Node node : nodes) {
            node.out = new ArrayList<Node>();
            for (String ref : node.refs) {
                Node to = map.get(ref);
                if (to != null) node.out.add(to);
            }
        }
    }

    // Find the refs from other nodes into each node.
    void reverseRefs() {
        for (Node node : nodes) node.in = new ArrayList<Node>();
        for (Node node : nodes) {
            for (Node to : node.out) to.in.add(node);
        }
    }

    // Find the cyclically dependent groups using Kosaraju's algorithm.
    // See https://en.wikipedia.org/wiki/Kosaraju's_algorithm
    private void findGroups() {
        groups = new ArrayList<Set<Node>>();
        List<Node> list = new ArrayList<Node>();
        for (Node node : nodes) node.visited = false;
        for (Node node : nodes) node.group = null;
        for (Node node : nodes) visit(list, node);
        for (Node node : list) assign(node, node);
    }

    // Visit a node during the algorithm.
    private void visit(List<Node> list, Node node) {
        if (node.visited) return;
        node.visited = true;
        for (Node to : node.out) visit(list, to);
        list.add(0, node);
    }

    // Assign a node to a group during the algorithm.
    private void assign(Node node, Node root) {
        if (node.group != null) return;
        if (node == root) {
            root.group = new HashSet<Node>();
            groups.add(0, root.group);
        }
        root.group.add(node);
        node.group = root.group;
        for (Node from : node.in) assign(from, root);
    }

    // Print out the groups, and their dependencies.
    void print() {
        for (Set<Node> g : groups) {
            if (g.size() == 1) {
                Node node = g.iterator().next();
                System.out.println(node + " -> " + node.out);
            } else {
                System.out.println(g);
                for (Node node : g) {
                    System.out.println("    " + node + " -> " + node.out);
                }
            }
        }
    }
}