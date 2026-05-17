package com.example.mysql.btree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

public class BPlusTreeIndexDemo {
    public static void run() {
        System.out.println("\n========== B+树索引：页分裂、合并、高度与IO次数 ==========");

        BPlusTree tree = new BPlusTree(3);
        for (int key : List.of(10, 20, 5, 6, 12, 30, 7, 17, 3, 4, 40, 50)) {
            tree.insert(key, "row-" + key);
        }

        tree.drainEvents().forEach(System.out::println);
        tree.printTree();
        System.out.printf("树高=%d，点查 key=17 约需IO=%d，范围查[6,30]约需IO=%d%n",
                tree.height(),
                tree.estimatedPointLookupIo(),
                tree.estimatedRangeLookupIo(6, 30));

        System.out.println("搜索 key=17 => " + tree.search(17).orElse("未命中"));
        tree.delete(3);
        tree.delete(4);
        tree.delete(5);
        System.out.println("删除 3、4、5 后触发叶子页借位/合并：");
        tree.drainEvents().forEach(System.out::println);
        tree.printTree();
        System.out.printf("合并后树高=%d，点查约需IO=%d%n", tree.height(), tree.estimatedPointLookupIo());
    }

    public static class BPlusTree {
        private final int maxKeysPerPage;
        private final List<String> events = new ArrayList<>();
        private Node root;

        public BPlusTree(int maxKeysPerPage) {
            if (maxKeysPerPage < 3) {
                throw new IllegalArgumentException("maxKeysPerPage 至少为 3，方便观察分裂和合并");
            }
            this.maxKeysPerPage = maxKeysPerPage;
            this.root = new LeafNode();
        }

        public void insert(int key, String value) {
            LeafNode leaf = findLeaf(key);
            int pos = Collections.binarySearch(leaf.keys, key);
            if (pos >= 0) {
                leaf.values.set(pos, value);
                return;
            }
            int insertAt = -pos - 1;
            leaf.keys.add(insertAt, key);
            leaf.values.add(insertAt, value);

            if (leaf.keys.size() > maxKeysPerPage) {
                splitLeaf(leaf);
            }
        }

        public Optional<String> search(int key) {
            LeafNode leaf = findLeaf(key);
            int pos = Collections.binarySearch(leaf.keys, key);
            return pos >= 0 ? Optional.of(leaf.values.get(pos)) : Optional.empty();
        }

        public void delete(int key) {
            LeafNode leaf = findLeaf(key);
            int pos = Collections.binarySearch(leaf.keys, key);
            if (pos < 0) {
                return;
            }
            leaf.keys.remove(pos);
            leaf.values.remove(pos);

            if (leaf == root) {
                return;
            }
            if (leaf.keys.size() < minLeafKeys()) {
                rebalanceLeaf(leaf);
            }
        }

        public int height() {
            int height = 1;
            Node node = root;
            while (!node.isLeaf()) {
                height++;
                node = ((InternalNode) node).children.get(0);
            }
            return height;
        }

        public int estimatedPointLookupIo() {
            return height();
        }

        public int estimatedRangeLookupIo(int startInclusive, int endInclusive) {
            LeafNode leaf = findLeaf(startInclusive);
            int leafPages = 0;
            while (leaf != null) {
                if (!leaf.keys.isEmpty() && leaf.keys.get(0) > endInclusive) {
                    break;
                }
                if (leaf.keys.stream().anyMatch(k -> k >= startInclusive && k <= endInclusive)) {
                    leafPages++;
                }
                leaf = leaf.next;
            }
            return Math.max(1, height() + leafPages - 1);
        }

        public void printTree() {
            Queue<Node> queue = new ArrayDeque<>();
            queue.add(root);
            int level = 1;
            while (!queue.isEmpty()) {
                int size = queue.size();
                System.out.print("level " + level + " => ");
                for (int i = 0; i < size; i++) {
                    Node node = queue.poll();
                    System.out.print(node.keys + (node.isLeaf() ? " " : "-> "));
                    if (!node.isLeaf()) {
                        queue.addAll(((InternalNode) node).children);
                    }
                }
                System.out.println();
                level++;
            }
        }

        public List<String> drainEvents() {
            List<String> snapshot = new ArrayList<>(events);
            events.clear();
            return snapshot;
        }

        private LeafNode findLeaf(int key) {
            Node node = root;
            while (!node.isLeaf()) {
                InternalNode internal = (InternalNode) node;
                int childIndex = firstGreaterThan(internal.keys, key);
                node = internal.children.get(childIndex);
            }
            return (LeafNode) node;
        }

        private void splitLeaf(LeafNode leaf) {
            int splitFrom = (leaf.keys.size() + 1) / 2;
            LeafNode right = new LeafNode();
            right.parent = leaf.parent;

            right.keys.addAll(leaf.keys.subList(splitFrom, leaf.keys.size()));
            right.values.addAll(leaf.values.subList(splitFrom, leaf.values.size()));
            leaf.keys.subList(splitFrom, leaf.keys.size()).clear();
            leaf.values.subList(splitFrom, leaf.values.size()).clear();

            right.next = leaf.next;
            if (right.next != null) {
                right.next.previous = right;
            }
            leaf.next = right;
            right.previous = leaf;

            events.add("叶子页分裂：" + leaf.keys + " | " + right.keys + "，向父节点提升分隔键 " + right.keys.get(0));
            insertIntoParent(leaf, right.keys.get(0), right);
        }

        private void splitInternal(InternalNode internal) {
            int middle = internal.keys.size() / 2;
            int separator = internal.keys.get(middle);

            InternalNode right = new InternalNode();
            right.parent = internal.parent;
            right.keys.addAll(internal.keys.subList(middle + 1, internal.keys.size()));
            right.children.addAll(internal.children.subList(middle + 1, internal.children.size()));
            for (Node child : right.children) {
                child.parent = right;
            }

            internal.keys.subList(middle, internal.keys.size()).clear();
            internal.children.subList(middle + 1, internal.children.size()).clear();

            events.add("内部页分裂：提升分隔键 " + separator + "，树可能增高");
            insertIntoParent(internal, separator, right);
        }

        private void insertIntoParent(Node left, int separator, Node right) {
            if (left.parent == null) {
                InternalNode newRoot = new InternalNode();
                newRoot.keys.add(separator);
                newRoot.children.add(left);
                newRoot.children.add(right);
                left.parent = newRoot;
                right.parent = newRoot;
                root = newRoot;
                return;
            }

            InternalNode parent = left.parent;
            int leftIndex = parent.children.indexOf(left);
            parent.keys.add(leftIndex, separator);
            parent.children.add(leftIndex + 1, right);
            right.parent = parent;

            if (parent.keys.size() > maxKeysPerPage) {
                splitInternal(parent);
            }
        }

        private void rebalanceLeaf(LeafNode leaf) {
            InternalNode parent = leaf.parent;
            int index = parent.children.indexOf(leaf);
            LeafNode left = index > 0 && parent.children.get(index - 1).isLeaf()
                    ? (LeafNode) parent.children.get(index - 1)
                    : null;
            LeafNode right = index + 1 < parent.children.size() && parent.children.get(index + 1).isLeaf()
                    ? (LeafNode) parent.children.get(index + 1)
                    : null;

            if (left != null && left.keys.size() > minLeafKeys()) {
                leaf.keys.add(0, left.keys.remove(left.keys.size() - 1));
                leaf.values.add(0, left.values.remove(left.values.size() - 1));
                parent.keys.set(index - 1, leaf.keys.get(0));
                events.add("叶子页借位：从左兄弟借到 key=" + leaf.keys.get(0));
                return;
            }

            if (right != null && right.keys.size() > minLeafKeys()) {
                leaf.keys.add(right.keys.remove(0));
                leaf.values.add(right.values.remove(0));
                parent.keys.set(index, right.keys.get(0));
                events.add("叶子页借位：从右兄弟借到 key=" + leaf.keys.get(leaf.keys.size() - 1));
                return;
            }

            if (left != null) {
                left.keys.addAll(leaf.keys);
                left.values.addAll(leaf.values);
                left.next = leaf.next;
                if (leaf.next != null) {
                    leaf.next.previous = left;
                }
                events.add("叶子页合并：并入左兄弟，合并后=" + left.keys);
                removeChildFromParent(parent, index - 1, index);
            } else if (right != null) {
                leaf.keys.addAll(right.keys);
                leaf.values.addAll(right.values);
                leaf.next = right.next;
                if (right.next != null) {
                    right.next.previous = leaf;
                }
                events.add("叶子页合并：吸收右兄弟，合并后=" + leaf.keys);
                removeChildFromParent(parent, index, index + 1);
            }
        }

        private void rebalanceInternal(InternalNode node) {
            if (node == root || node.keys.size() >= minInternalKeys()) {
                return;
            }

            InternalNode parent = node.parent;
            int index = parent.children.indexOf(node);
            InternalNode left = index > 0 && !parent.children.get(index - 1).isLeaf()
                    ? (InternalNode) parent.children.get(index - 1)
                    : null;
            InternalNode right = index + 1 < parent.children.size() && !parent.children.get(index + 1).isLeaf()
                    ? (InternalNode) parent.children.get(index + 1)
                    : null;

            if (left != null && left.keys.size() > minInternalKeys()) {
                node.keys.add(0, parent.keys.get(index - 1));
                parent.keys.set(index - 1, left.keys.remove(left.keys.size() - 1));
                Node movedChild = left.children.remove(left.children.size() - 1);
                node.children.add(0, movedChild);
                movedChild.parent = node;
                return;
            }

            if (right != null && right.keys.size() > minInternalKeys()) {
                node.keys.add(parent.keys.get(index));
                parent.keys.set(index, right.keys.remove(0));
                Node movedChild = right.children.remove(0);
                node.children.add(movedChild);
                movedChild.parent = node;
                return;
            }

            if (left != null) {
                left.keys.add(parent.keys.get(index - 1));
                left.keys.addAll(node.keys);
                for (Node child : node.children) {
                    child.parent = left;
                }
                left.children.addAll(node.children);
                removeChildFromParent(parent, index - 1, index);
            } else if (right != null) {
                node.keys.add(parent.keys.get(index));
                node.keys.addAll(right.keys);
                for (Node child : right.children) {
                    child.parent = node;
                }
                node.children.addAll(right.children);
                removeChildFromParent(parent, index, index + 1);
            }
        }

        private void removeChildFromParent(InternalNode parent, int keyIndex, int childIndex) {
            parent.keys.remove(keyIndex);
            parent.children.remove(childIndex);

            if (parent == root && parent.keys.isEmpty()) {
                root = parent.children.get(0);
                root.parent = null;
                return;
            }

            if (parent != root && parent.keys.size() < minInternalKeys()) {
                rebalanceInternal(parent);
            }
        }

        private int minLeafKeys() {
            return (maxKeysPerPage + 1) / 2;
        }

        private int minInternalKeys() {
            return Math.max(1, ((maxKeysPerPage + 1) / 2) - 1);
        }

        private int firstGreaterThan(List<Integer> keys, int key) {
            int index = 0;
            while (index < keys.size() && key >= keys.get(index)) {
                index++;
            }
            return index;
        }
    }

    private abstract static class Node {
        protected final List<Integer> keys = new ArrayList<>();
        protected InternalNode parent;

        abstract boolean isLeaf();
    }

    private static class InternalNode extends Node {
        private final List<Node> children = new ArrayList<>();

        @Override
        boolean isLeaf() {
            return false;
        }
    }

    private static class LeafNode extends Node {
        private final List<String> values = new ArrayList<>();
        private LeafNode next;
        private LeafNode previous;

        @Override
        boolean isLeaf() {
            return true;
        }
    }
}
