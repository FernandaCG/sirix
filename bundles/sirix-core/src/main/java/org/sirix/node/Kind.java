/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.sirix.node;

import static org.sirix.node.Utils.getVarLong;
import static org.sirix.node.Utils.putVarLong;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnegative;
import org.brackit.xquery.atomic.Atomic;
import org.brackit.xquery.atomic.QNm;
import org.brackit.xquery.module.Namespaces;
import org.brackit.xquery.xdm.Type;
import org.sirix.access.conf.ResourceConfiguration;
import org.sirix.api.PageReadOnlyTrx;
import org.sirix.index.AtomicUtil;
import org.sirix.index.avltree.AVLNode;
import org.sirix.index.avltree.keyvalue.CASValue;
import org.sirix.index.avltree.keyvalue.NodeReferences;
import org.sirix.index.path.summary.PathNode;
import org.sirix.node.delegates.NameNodeDelegate;
import org.sirix.node.delegates.NodeDelegate;
import org.sirix.node.delegates.StructNodeDelegate;
import org.sirix.node.delegates.ValNodeDelegate;
import org.sirix.node.interfaces.NodePersistenter;
import org.sirix.node.interfaces.Record;
import org.sirix.node.json.ArrayNode;
import org.sirix.node.json.BooleanNode;
import org.sirix.node.json.JsonDocumentRootNode;
import org.sirix.node.json.NullNode;
import org.sirix.node.json.NumberNode;
import org.sirix.node.json.ObjectKeyNode;
import org.sirix.node.json.ObjectNode;
import org.sirix.node.json.StringNode;
import org.sirix.node.xdm.AttributeNode;
import org.sirix.node.xdm.CommentNode;
import org.sirix.node.xdm.ElementNode;
import org.sirix.node.xdm.NamespaceNode;
import org.sirix.node.xdm.PINode;
import org.sirix.node.xdm.TextNode;
import org.sirix.node.xdm.XdmDocumentRootNode;
import org.sirix.page.UnorderedKeyValuePage;
import org.sirix.service.xml.xpath.AtomicValue;
import org.sirix.settings.Constants;
import org.sirix.settings.Fixed;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

/**
 * Enumeration for different nodes. All nodes are determined by a unique id.
 *
 * @author Sebastian Graf, University of Konstanz
 * @author Johannes Lichtenberger, University of Konstanz
 *
 */
public enum Kind implements NodePersistenter {

  /** Node kind is element. */
  ELEMENT((byte) 1, ElementNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Name delegate.
      final NameNodeDelegate nameDel = deserializeNameDelegate(nodeDel, source);

      // Attributes.
      final int attrCount = source.readInt();
      final List<Long> attrKeys = new ArrayList<>(attrCount);
      final BiMap<Long, Long> attrs = HashBiMap.<Long, Long>create();
      for (int i = 0; i < attrCount; i++) {
        final long nodeKey = source.readLong();
        attrKeys.add(nodeKey);
        attrs.put(source.readLong(), nodeKey);
      }

      // Namespaces.
      final int nsCount = source.readInt();
      final List<Long> namespKeys = new ArrayList<>(nsCount);
      for (int i = 0; i < nsCount; i++) {
        namespKeys.add(source.readLong());
      }

      final String uri = pageReadTrx.getName(nameDel.getURIKey(), Kind.NAMESPACE);
      final int prefixKey = nameDel.getPrefixKey();
      final String prefix = prefixKey == -1
          ? ""
          : pageReadTrx.getName(prefixKey, Kind.ELEMENT);
      final int localNameKey = nameDel.getLocalNameKey();
      final String localName = localNameKey == -1
          ? ""
          : pageReadTrx.getName(localNameKey, Kind.ELEMENT);

      return new ElementNode(structDel, nameDel, attrKeys, attrs, namespKeys, new QNm(uri, prefix, localName));
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final ElementNode node = (ElementNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
      serializeNameDelegate(node.getNameNodeDelegate(), sink);
      sink.writeInt(node.getAttributeCount());
      for (int i = 0, attCount = node.getAttributeCount(); i < attCount; i++) {
        final long key = node.getAttributeKey(i);
        sink.writeLong(key);
        sink.writeLong(node.getAttributeNameKey(key).get());
      }
      sink.writeInt(node.getNamespaceCount());
      for (int i = 0, nspCount = node.getNamespaceCount(); i < nspCount; i++) {
        sink.writeLong(node.getNamespaceKey(i));
      }
    }
  },

  /** Node kind is attribute. */
  ATTRIBUTE((byte) 2, AttributeNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Name delegate.
      final NameNodeDelegate nameDel = deserializeNameDelegate(nodeDel, source);

      // Val delegate.
      final boolean isCompressed = source.readByte() == (byte) 1
          ? true
          : false;
      final byte[] vals = new byte[source.readInt()];
      source.readFully(vals, 0, vals.length);
      final ValNodeDelegate valDel = new ValNodeDelegate(nodeDel, vals, isCompressed);

      final String uri = pageReadTrx.getName(nameDel.getURIKey(), Kind.NAMESPACE);
      final int prefixKey = nameDel.getPrefixKey();
      final String prefix = prefixKey == -1
          ? ""
          : pageReadTrx.getName(prefixKey, Kind.ATTRIBUTE);
      final int localNameKey = nameDel.getLocalNameKey();
      final String localName = localNameKey == -1
          ? ""
          : pageReadTrx.getName(localNameKey, Kind.ATTRIBUTE);

      final QNm name = new QNm(uri, prefix, localName);

      // Returning an instance.
      return new AttributeNode(nodeDel, nameDel, valDel, name);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final AttributeNode node = (AttributeNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeNameDelegate(node.getNameNodeDelegate(), sink);
      serializeValDelegate(node.getValNodeDelegate(), sink);
    }
  },

  /** Node kind is namespace. */
  NAMESPACE((byte) 13, NamespaceNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Name delegate.
      final NameNodeDelegate nameDel = deserializeNameDelegate(nodeDel, source);

      final String uri = pageReadTrx.getName(nameDel.getURIKey(), Kind.NAMESPACE);
      final int prefixKey = nameDel.getPrefixKey();
      final String prefix = prefixKey == -1
          ? ""
          : pageReadTrx.getName(prefixKey, Kind.ELEMENT);
      final int localNameKey = nameDel.getLocalNameKey();
      final String localName = localNameKey == -1
          ? ""
          : pageReadTrx.getName(localNameKey, Kind.ELEMENT);

      final QNm name = new QNm(uri, prefix, localName);

      return new NamespaceNode(nodeDel, nameDel, name);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final NamespaceNode node = (NamespaceNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeNameDelegate(node.getNameNodeDelegate(), sink);
    }
  },

  /** Node kind is text. */
  TEXT((byte) 3, TextNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Val delegate.
      final boolean isCompressed = source.readByte() == (byte) 1
          ? true
          : false;
      final byte[] vals = new byte[source.readInt()];
      source.readFully(vals, 0, vals.length);
      final ValNodeDelegate valDel = new ValNodeDelegate(nodeDel, vals, isCompressed);

      // Struct delegate.
      final long nodeKey = nodeDel.getNodeKey();
      final StructNodeDelegate structDel = new StructNodeDelegate(nodeDel, Fixed.NULL_NODE_KEY.getStandardProperty(),
          nodeKey - getVarLong(source), nodeKey - getVarLong(source), 0L, 0L);

      // Returning an instance.
      return new TextNode(valDel, structDel);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final TextNode node = (TextNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeValDelegate(node.getValNodeDelegate(), sink);
      final StructNodeDelegate del = node.getStructNodeDelegate();
      final long nodeKey = node.getNodeKey();
      putVarLong(sink, nodeKey - del.getRightSiblingKey());
      putVarLong(sink, nodeKey - del.getLeftSiblingKey());
    }
  },

  /** Node kind is processing instruction. */
  PROCESSING_INSTRUCTION((byte) 7, PINode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Name delegate.
      final NameNodeDelegate nameDel = deserializeNameDelegate(nodeDel, source);

      // Val delegate.
      final boolean isCompressed = source.readByte() == (byte) 1
          ? true
          : false;
      final byte[] vals = new byte[source.readInt()];
      source.readFully(vals, 0, vals.length);
      final ValNodeDelegate valDel = new ValNodeDelegate(nodeDel, vals, isCompressed);

      // Returning an instance.
      return new PINode(structDel, nameDel, valDel, pageReadTrx);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final PINode node = (PINode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
      serializeNameDelegate(node.getNameNodeDelegate(), sink);
      serializeValDelegate(node.getValNodeDelegate(), sink);
    }
  },

  /** Node kind is comment. */
  COMMENT((byte) 8, CommentNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Val delegate.
      final boolean isCompressed = source.readByte() == (byte) 1
          ? true
          : false;
      final byte[] vals = new byte[source.readInt()];
      source.readFully(vals, 0, vals.length);
      final ValNodeDelegate valDel = new ValNodeDelegate(nodeDel, vals, isCompressed);

      // Struct delegate.
      final long nodeKey = nodeDel.getNodeKey();
      final StructNodeDelegate structDel = new StructNodeDelegate(nodeDel, Fixed.NULL_NODE_KEY.getStandardProperty(),
          nodeKey - getVarLong(source), nodeKey - getVarLong(source), 0L, 0L);

      // Returning an instance.
      return new CommentNode(valDel, structDel);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final CommentNode node = (CommentNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeValDelegate(node.getValNodeDelegate(), sink);
      final StructNodeDelegate del = node.getStructNodeDelegate();
      final long nodeKey = node.getNodeKey();
      putVarLong(sink, nodeKey - del.getRightSiblingKey());
      putVarLong(sink, nodeKey - del.getLeftSiblingKey());
    }
  },

  /** Node kind is document root. */
  // Virtualize document root node?
  XDM_DOCUMENT((byte) 9, XdmDocumentRootNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final NodeDelegate nodeDel = new NodeDelegate(Fixed.DOCUMENT_NODE_KEY.getStandardProperty(),
          Fixed.NULL_NODE_KEY.getStandardProperty(), source.readLong(), getVarLong(source), SirixDeweyID.newRootID());
      final StructNodeDelegate structDel =
          new StructNodeDelegate(nodeDel, getVarLong(source), Fixed.NULL_NODE_KEY.getStandardProperty(),
              Fixed.NULL_NODE_KEY.getStandardProperty(), source.readByte() == ((byte) 0)
                  ? 0
                  : 1,
              source.readLong());
      return new XdmDocumentRootNode(nodeDel, structDel);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final XdmDocumentRootNode node = (XdmDocumentRootNode) record;
      sink.writeLong(node.getHash());
      putVarLong(sink, node.getRevision());
      putVarLong(sink, node.getFirstChildKey());
      sink.writeByte(node.hasFirstChild()
          ? (byte) 1
          : (byte) 0);
      sink.writeLong(node.getDescendantCount());
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      return null;
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {}
  },

  /** Whitespace text. */
  WHITESPACE((byte) 4, null) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      return null;
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {}
  },

  /** Node kind is deleted node. */
  DELETE((byte) 5, DeletedNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) {
      final NodeDelegate delegate = new NodeDelegate(recordID, 0, 0, 0, null);
      return new DeletedNode(delegate);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx) {}

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      return Optional.empty();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {}
  },

  /** NullNode to support the Null Object pattern. */
  NULL((byte) 6, NullNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serialize(final DataOutput ink, final Record record, final PageReadOnlyTrx pageReadTrx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      return null;
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {}
  },

  /** Dumb node for testing. */
  DUMB((byte) 20, DumbNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) {
      return new DumbNode(recordID);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** AtomicKind. */
  ATOMIC((byte) 15, AtomicValue.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** Node kind is path node. */
  PATH((byte) 16, PathNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegateWithoutIDs(source, recordID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Name delegate.
      final NameNodeDelegate nameDel = deserializeNameDelegate(nodeDel, source);

      return new PathNode(nodeDel, structDel, nameDel, Kind.getKind(source.readByte()), source.readInt(),
          source.readInt());
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final PathNode node = (PathNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
      serializeNameDelegate(node.getNameNodeDelegate(), sink);
      sink.writeByte(node.getPathKind().getId());
      sink.writeInt(node.getReferences());
      sink.writeInt(node.getLevel());
    };

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** Node kind is a CAS-AVL node. */
  CASAVL((byte) 17, AVLNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final int valueSize = source.readInt();
      final byte[] value = new byte[valueSize];
      source.readFully(value, 0, valueSize);
      final int typeSize = source.readInt();
      final byte[] type = new byte[typeSize];
      source.readFully(type, 0, typeSize);
      final int keySize = source.readInt();
      final Set<Long> nodeKeys = new HashSet<>(keySize);
      long key = getVarLong(source);
      nodeKeys.add(key);
      for (int i = 1; i < keySize; i++) {
        if (i + 1 < keySize) {
          key += getVarLong(source);
          nodeKeys.add(key);
        }
      }
      final Type atomicType = resolveType(new String(type, Constants.DEFAULT_ENCODING));

      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegateWithoutIDs(source, recordID, pageReadTrx);
      final long leftChild = getVarLong(source);
      final long rightChild = getVarLong(source);
      final long pathNodeKey = getVarLong(source);
      final boolean isChanged = source.readBoolean();

      final Atomic atomic = AtomicUtil.fromBytes(value, atomicType);
      AVLNode<CASValue, NodeReferences> node;
      node = new AVLNode<CASValue, NodeReferences>(new CASValue(atomic, atomicType, pathNodeKey),
          new NodeReferences(nodeKeys), nodeDel);

      node.setLeftChildKey(leftChild);
      node.setRightChildKey(rightChild);
      node.setChanged(isChanged);
      return node;
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      @SuppressWarnings("unchecked")
      final AVLNode<CASValue, NodeReferences> node = (AVLNode<CASValue, NodeReferences>) record;
      final CASValue key = node.getKey();
      final byte[] textValue = key.getValue();
      sink.writeInt(textValue.length);
      sink.write(textValue);
      final byte[] type = key.getType().toString().getBytes(Constants.DEFAULT_ENCODING);
      sink.writeInt(type.length);
      sink.write(type);
      final NodeReferences value = node.getValue();
      final Set<Long> nodeKeys = value.getNodeKeys();

      // Store in a list and sort the list.
      final List<Long> listNodeKeys = new ArrayList<>(nodeKeys);
      Collections.sort(listNodeKeys);
      sink.writeInt(listNodeKeys.size());
      putVarLong(sink, listNodeKeys.get(0));
      for (int i = 0; i < listNodeKeys.size(); i++) {
        if (i + 1 < listNodeKeys.size()) {
          final long diff = listNodeKeys.get(i + 1) - listNodeKeys.get(i);
          putVarLong(sink, diff);
        }
      }
      serializeDelegate(node.getNodeDelegate(), sink);
      putVarLong(sink, node.getLeftChildKey());
      putVarLong(sink, node.getRightChildKey());
      putVarLong(sink, key.getPathNodeKey());
      sink.writeBoolean(node.isChanged());
    };

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    private Type resolveType(final String s) {
      final QNm name =
          new QNm(Namespaces.XS_NSURI, Namespaces.XS_PREFIX, s.substring(Namespaces.XS_PREFIX.length() + 1));
      for (final Type type : Type.builtInTypes) {
        if (type.getName().getLocalName().equals(name.getLocalName())) {
          return type;
        }
      }
      throw new IllegalStateException("Unknown content type: " + name);
    }
  },

  /** Node kind is a PATH-AVL node. */
  PATHAVL((byte) 18, AVLNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final long key = getVarLong(source);
      final int keySize = source.readInt();
      final Set<Long> nodeKeys = new HashSet<>(keySize);
      for (int i = 0; i < keySize; i++) {
        nodeKeys.add(source.readLong());
      }
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegateWithoutIDs(source, recordID, pageReadTrx);
      final long leftChild = getVarLong(source);
      final long rightChild = getVarLong(source);
      final boolean isChanged = source.readBoolean();
      final AVLNode<Long, NodeReferences> node = new AVLNode<>(key, new NodeReferences(nodeKeys), nodeDel);
      node.setLeftChildKey(leftChild);
      node.setRightChildKey(rightChild);
      node.setChanged(isChanged);
      return node;
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      @SuppressWarnings("unchecked")
      final AVLNode<Long, NodeReferences> node = (AVLNode<Long, NodeReferences>) record;
      putVarLong(sink, node.getKey().longValue());
      final NodeReferences value = node.getValue();
      final Set<Long> nodeKeys = value.getNodeKeys();
      sink.writeInt(nodeKeys.size());
      for (final long nodeKey : nodeKeys) {
        sink.writeLong(nodeKey);
      }
      serializeDelegate(node.getNodeDelegate(), sink);
      putVarLong(sink, node.getLeftChildKey());
      putVarLong(sink, node.getRightChildKey());
      sink.writeBoolean(node.isChanged());
    };

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** Node kind is a PATH-AVL node. */
  NAMEAVL((byte) 19, AVLNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final byte[] nspBytes = new byte[source.readInt()];
      source.readFully(nspBytes);
      final byte[] prefixBytes = new byte[source.readInt()];
      source.readFully(prefixBytes);
      final byte[] localNameBytes = new byte[source.readInt()];
      source.readFully(localNameBytes);
      final QNm name = new QNm(new String(nspBytes, Constants.DEFAULT_ENCODING),
          new String(prefixBytes, Constants.DEFAULT_ENCODING), new String(localNameBytes, Constants.DEFAULT_ENCODING));
      final int keySize = source.readInt();
      final Set<Long> nodeKeys = new HashSet<>(keySize);
      for (int i = 0; i < keySize; i++) {
        nodeKeys.add(source.readLong());
      }
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegateWithoutIDs(source, recordID, pageReadTrx);
      final long leftChild = getVarLong(source);
      final long rightChild = getVarLong(source);
      final boolean isChanged = source.readBoolean();
      final AVLNode<QNm, NodeReferences> node = new AVLNode<>(name, new NodeReferences(nodeKeys), nodeDel);
      node.setLeftChildKey(leftChild);
      node.setRightChildKey(rightChild);
      node.setChanged(isChanged);
      return node;
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      @SuppressWarnings("unchecked")
      final AVLNode<QNm, NodeReferences> node = (AVLNode<QNm, NodeReferences>) record;
      final byte[] nspBytes = node.getKey().getNamespaceURI().getBytes();
      sink.writeInt(nspBytes.length);
      sink.write(nspBytes);
      final byte[] prefixBytes = node.getKey().getPrefix().getBytes();
      sink.writeInt(prefixBytes.length);
      sink.write(prefixBytes);
      final byte[] localNameBytes = node.getKey().getLocalName().getBytes();
      sink.writeInt(localNameBytes.length);
      sink.write(localNameBytes);
      final NodeReferences value = node.getValue();
      final Set<Long> nodeKeys = value.getNodeKeys();
      sink.writeInt(nodeKeys.size());
      for (final long nodeKey : nodeKeys) {
        sink.writeLong(nodeKey);
      }
      serializeDelegate(node.getNodeDelegate(), sink);
      putVarLong(sink, node.getLeftChildKey());
      putVarLong(sink, node.getRightChildKey());
      sink.writeBoolean(node.isChanged());
    };

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** Node includes a deweyID <=> nodeKey mapping. */
  DEWEYIDMAPPING((byte) 23, DeweyIDMappingNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** JSON object node. */
  JSON_OBJECT((byte) 24, ObjectNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Returning an instance.
      return new ObjectNode(structDel);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final ObjectNode node = (ObjectNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** JSON array node. */
  JSON_ARRAY((byte) 25, ArrayNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final long pathNodeKey = source.readLong();

      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Returning an instance.
      return new ArrayNode(structDel, pathNodeKey);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final ArrayNode node = (ArrayNode) record;
      sink.writeLong(node.getPathNodeKey());
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** JSON array node. */
  JSON_OBJECT_KEY((byte) 26, ObjectKeyNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final int nameKey = source.readInt();
      final long pathNodeKey = getVarLong(source);
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      final String name = nameKey == -1
          ? ""
          : pageReadTrx.getName(nameKey, Kind.JSON_OBJECT_KEY);

      // Returning an instance.
      return new ObjectKeyNode(structDel, nameKey, name, pathNodeKey);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final ObjectKeyNode node = (ObjectKeyNode) record;
      sink.writeInt(node.getNameKey());
      putVarLong(sink, node.getPathNodeKey());
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** JSON string value node. */
  JSON_STRING_VALUE((byte) 30, StringNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Val delegate.
      final boolean isCompressed = source.readByte() == (byte) 1
          ? true
          : false;
      final byte[] vals = new byte[source.readInt()];
      source.readFully(vals, 0, vals.length);
      final ValNodeDelegate valDel = new ValNodeDelegate(nodeDel, vals, isCompressed);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Returning an instance.
      return new StringNode(valDel, structDel);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final StringNode node = (StringNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeValDelegate(node.getValNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** JSON boolean value node. */
  JSON_BOOLEAN_VALUE((byte) 27, BooleanNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final boolean boolValue = source.readBoolean();
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Returning an instance.
      return new BooleanNode(boolValue, structDel);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final BooleanNode node = (BooleanNode) record;
      sink.writeBoolean(node.getValue());
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },

  /** JSON number value node. */
  JSON_NUMBER_VALUE((byte) 28, NumberNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final byte valueType = source.readByte();
      final Number number;

      switch (valueType) {
        case 0:
          number = source.readDouble();
          break;
        case 1:
          number = source.readFloat();
          break;
        case 2:
          number = source.readInt();
          break;
        case 3:
          number = source.readLong();
          break;
        case 4:
          number = deserializeBigInteger(source);
          break;
        case 5:
          final BigInteger bigInt = deserializeBigInteger(source);
          final int scale = source.readInt();
          number = new BigDecimal(bigInt, scale);
          break;
        default:
          throw new AssertionError("Type not known.");
      }

      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Returning an instance.
      return new NumberNode(number, structDel);
    }

    private BigInteger deserializeBigInteger(final DataInput source) throws IOException {
      final byte[] bytes = new byte[source.readInt()];
      source.readFully(bytes);
      return new BigInteger(bytes);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final NumberNode node = (NumberNode) record;
      final Number number = node.getValue();

      if (number instanceof Double) {
        sink.writeByte(0);
        sink.writeDouble(number.doubleValue());
      } else if (number instanceof Float) {
        sink.writeByte(1);
        sink.writeFloat(number.floatValue());
      } else if (number instanceof Integer) {
        sink.writeByte(2);
        sink.writeInt(number.intValue());
      } else if (number instanceof Long) {
        sink.writeByte(3);
        sink.writeLong(number.longValue());
      } else if (number instanceof BigInteger) {
        sink.writeByte(4);
        serializeBigInteger(sink, (BigInteger) number);
      } else if (number instanceof BigDecimal) {
        sink.writeByte(5);
        final BigDecimal value = (BigDecimal) number;
        final BigInteger bigInt = value.unscaledValue();
        final int scale = value.scale();
        serializeBigInteger(sink, bigInt);
        sink.writeInt(scale);
      } else {
        throw new AssertionError("Type not known.");
      }

      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
    }

    private void serializeBigInteger(final DataOutput sink, final BigInteger bigInteger) throws IOException {
      final byte[] bytes = bigInteger.toByteArray();
      sink.writeInt(bytes.length);
      sink.write(bytes);
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },
  /** JSON null node. */
  JSON_NULL_VALUE((byte) 29, NullNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      // Node delegate.
      final NodeDelegate nodeDel = deserializeNodeDelegate(source, recordID, deweyID, pageReadTrx);

      // Struct delegate.
      final StructNodeDelegate structDel = deserializeStructDel(nodeDel, source);

      // Returning an instance.
      return new NullNode(structDel);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final NullNode node = (NullNode) record;
      serializeDelegate(node.getNodeDelegate(), sink);
      serializeStructDelegate(node.getStructNodeDelegate(), sink);
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  },
  /** Node kind is document root. */
  // Virtualize document root node?
  JSON_DOCUMENT((byte) 31, JsonDocumentRootNode.class) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      final NodeDelegate nodeDel = new NodeDelegate(Fixed.DOCUMENT_NODE_KEY.getStandardProperty(),
          Fixed.NULL_NODE_KEY.getStandardProperty(), source.readLong(), getVarLong(source), null);
      final StructNodeDelegate structDel =
          new StructNodeDelegate(nodeDel, getVarLong(source), Fixed.NULL_NODE_KEY.getStandardProperty(),
              Fixed.NULL_NODE_KEY.getStandardProperty(), source.readByte() == ((byte) 0)
                  ? 0
                  : 1,
              source.readLong());
      return new JsonDocumentRootNode(nodeDel, structDel);
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      final JsonDocumentRootNode node = (JsonDocumentRootNode) record;
      sink.writeLong(node.getHash());
      putVarLong(sink, node.getRevision());
      putVarLong(sink, node.getFirstChildKey());
      sink.writeByte(node.hasFirstChild()
          ? (byte) 1
          : (byte) 0);
      sink.writeLong(node.getDescendantCount());
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      return null;
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {}
  },

  /** Node type not known. */
  UNKNOWN((byte) 22, null) {
    @Override
    public Record deserialize(final DataInput source, final @Nonnegative long recordID, final SirixDeweyID deweyID,
        final PageReadOnlyTrx pageReadTrx) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serialize(final DataOutput sink, final Record record, final PageReadOnlyTrx pageReadTrx)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID prevDeweyID,
        ResourceConfiguration resourceConfig) throws IOException {
      throw new UnsupportedOperationException();
    }
  };

  /** Identifier. */
  private final byte mId;

  /** Class. */
  private final Class<? extends Record> mClass;

  /** Mapping of keys -> nodes. */
  private static final Map<Byte, Kind> INSTANCEFORID = new HashMap<>();

  /** Mapping of class -> nodes. */
  private static final Map<Class<? extends Record>, Kind> INSTANCEFORCLASS = new HashMap<>();

  static {
    for (final Kind node : values()) {
      INSTANCEFORID.put(node.mId, node);
      INSTANCEFORCLASS.put(node.mClass, node);
    }
  }

  /**
   * Constructor.
   *
   * @param id unique identifier
   * @param clazz class
   */
  private Kind(final byte id, final Class<? extends Record> clazz) {
    mId = id;
    mClass = clazz;
  }

  /**
   * Get the nodeKind.
   *
   * @return the unique kind
   */
  public byte getId() {
    return mId;
  }

  /**
   * Get class of node.
   *
   * @return class of node
   */
  public Class<? extends Record> getNodeClass() {
    return mClass;
  }

  /**
   * Public method to get the related node based on the identifier.
   *
   * @param id the identifier for the node
   * @return the related node
   */
  public static Kind getKind(final byte id) {
    return INSTANCEFORID.get(id);
  }

  /**
   * Public method to get the related node based on the class.
   *
   * @param clazz the class for the node
   * @return the related node
   */
  public static Kind getKind(final Class<? extends Record> clazz) {
    return INSTANCEFORCLASS.get(clazz);
  }

  @Override
  public Optional<SirixDeweyID> deserializeDeweyID(DataInput source, SirixDeweyID previousDeweyID,
      ResourceConfiguration resourceConfig) throws IOException {
    if (resourceConfig.areDeweyIDsStored) {
      if (previousDeweyID != null) {
        final byte[] previousDeweyIDBytes = previousDeweyID.toBytes();
        final int cutOffSize = source.readByte();
        final int size = source.readByte();
        final byte[] deweyIDBytes = new byte[size];
        source.readFully(deweyIDBytes);

        final byte[] bytes = new byte[cutOffSize + deweyIDBytes.length];
        final ByteBuffer target = ByteBuffer.wrap(bytes);
        target.put(Arrays.copyOfRange(previousDeweyIDBytes, 0, cutOffSize));
        target.put(deweyIDBytes);

        return Optional.of(new SirixDeweyID(bytes));
      } else {
        final byte deweyIDLength = source.readByte();
        final byte[] deweyIDBytes = new byte[deweyIDLength];
        source.readFully(deweyIDBytes, 0, deweyIDLength);
        return Optional.of(new SirixDeweyID(deweyIDBytes));
      }
    } else {
      return Optional.empty();
    }
  }

  @Override
  public void serializeDeweyID(DataOutput sink, Kind nodeKind, SirixDeweyID deweyID, SirixDeweyID nextDeweyID,
      ResourceConfiguration resourceConfig) throws IOException {
    if (resourceConfig.areDeweyIDsStored) {
      if (nextDeweyID != null) {
        final byte[] deweyIDBytes = deweyID.toBytes();
        final byte[] nextDeweyIDBytes = nextDeweyID.toBytes();

        assert deweyIDBytes.length <= nextDeweyIDBytes.length;

        int i = 0;
        for (; i < deweyIDBytes.length; i++) {
          if (deweyIDBytes[i] != nextDeweyIDBytes[i]) {
            break;
          }
        }
        writeDeweyID(sink, nextDeweyIDBytes, i);
      } else {
        final byte[] deweyIDBytes = deweyID.toBytes();
        sink.writeByte(deweyIDBytes.length);
        sink.write(deweyIDBytes);
      }
    }
  }

  /**
   * Deserialize node delegate without stored dewey IDs.
   *
   * @param source source to read from
   * @param resourceConfig resource configuration
   * @return {@link NodeDelegate} instance
   */
  private static final NodeDelegate deserializeNodeDelegateWithoutIDs(final DataInput source,
      final @Nonnegative long recordID, final PageReadOnlyTrx pageReadTrx) throws IOException {
    final long nodeKey = recordID;
    final long parentKey = nodeKey - getVarLong(source);
    final long hash = source.readLong();
    final long revision = getVarLong(source);
    return new NodeDelegate(nodeKey, parentKey, hash, revision, null);
  }

  /**
   * Deserialize node delegate.
   *
   * @param source source to read from
   * @param resourceConfig resource configuration
   * @return {@link NodeDelegate} instance
   */
  private static final NodeDelegate deserializeNodeDelegate(final DataInput source, final @Nonnegative long recordID,
      final SirixDeweyID id, final PageReadOnlyTrx pageReadTrx) throws IOException {
    final long nodeKey = recordID;
    final long parentKey = nodeKey - getVarLong(source);
    final long hash = source.readLong();
    final long revision = getVarLong(source);
    return new NodeDelegate(nodeKey, parentKey, hash, revision, id);
  }

  /**
   * Serializing the {@link NodeDelegate} instance.
   *
   * @param nodeDel node delegate
   * @param nextNode next node in the page or {@code null}
   * @param sink to serialize to
   * @param pageReadTrx {@link PageReadOnlyTrx} instance
   * @param {@link ResourceConfiguration} instance
   */
  private static final void serializeDelegate(final NodeDelegate nodeDel, final DataOutput sink) throws IOException {
    putVarLong(sink, nodeDel.getNodeKey() - nodeDel.getParentKey());
    sink.writeLong(nodeDel.getHash());
    putVarLong(sink, nodeDel.getRevision());
  }

  /**
   * Write the deweyID.
   *
   * @param sink to write to
   * @param deweyID deweyID in bytes
   * @param i the index from which to start the copy of the array
   */
  private static void writeDeweyID(final DataOutput sink, final byte[] deweyID, final @Nonnegative int i)
      throws IOException {
    sink.writeByte(i);
    sink.writeByte(deweyID.length - i);
    sink.write(Arrays.copyOfRange(deweyID, i, deweyID.length));
  }

  /**
   * Serializing the {@link StructNodeDelegate} instance.
   *
   * @param nodeDel to be serialize
   * @param sink to serialize to
   */
  private static final void serializeStructDelegate(final StructNodeDelegate nodeDel, final DataOutput sink)
      throws IOException {
    putVarLong(sink, nodeDel.getNodeKey() - nodeDel.getRightSiblingKey());
    putVarLong(sink, nodeDel.getNodeKey() - nodeDel.getLeftSiblingKey());
    putVarLong(sink, nodeDel.getNodeKey() - nodeDel.getFirstChildKey());
    putVarLong(sink, nodeDel.getChildCount());
    putVarLong(sink, nodeDel.getDescendantCount() - nodeDel.getChildCount());
  }

  /**
   * Deserialize struct delegate.
   *
   * @param nodeDel node delegate
   * @param source input source
   * @return {@link StructNodeDelegate} instance
   */
  private static final StructNodeDelegate deserializeStructDel(final NodeDelegate nodeDel, final DataInput source)
      throws IOException {
    final long currKey = nodeDel.getNodeKey();
    final long rightSibl = currKey - getVarLong(source);
    final long leftSibl = currKey - getVarLong(source);
    final long firstChild = currKey - getVarLong(source);
    final long childCount = getVarLong(source);
    final long descendantCount = getVarLong(source) + childCount;
    return new StructNodeDelegate(nodeDel, firstChild, rightSibl, leftSibl, childCount, descendantCount);
  }

  /**
   * Deserialize name node delegate.
   *
   * @param nodeDel {@link NodeDelegate} instance
   * @param source source to read from
   * @return {@link NameNodeDelegate} instance
   */
  private static final NameNodeDelegate deserializeNameDelegate(final NodeDelegate nodeDel, final DataInput source)
      throws IOException {
    final int uriKey = source.readInt();
    int prefixKey = source.readInt();
    int localNameKey = source.readInt();
    return new NameNodeDelegate(nodeDel, uriKey, prefixKey, localNameKey, getVarLong(source));
  }

  /**
   * Serializing the {@link NameNodeDelegate} instance.
   *
   * @param nameDel {@link NameNodeDelegate} instance
   * @param sink to serialize to
   */
  private static final void serializeNameDelegate(final NameNodeDelegate nameDel, final DataOutput sink)
      throws IOException {
    sink.writeInt(nameDel.getURIKey());
    sink.writeInt(nameDel.getPrefixKey());
    sink.writeInt(nameDel.getLocalNameKey());
    putVarLong(sink, nameDel.getPathNodeKey());
  }

  /**
   * Serializing the {@link ValNodeDelegate} instance.
   *
   * @param valueDel to be serialized
   * @param sink to serialize to
   */
  private static final void serializeValDelegate(final ValNodeDelegate valueDel, final DataOutput sink)
      throws IOException {
    final boolean isCompressed = valueDel.isCompressed();
    sink.writeByte(isCompressed
        ? (byte) 1
        : (byte) 0);
    final byte[] value = isCompressed
        ? valueDel.getCompressed()
        : valueDel.getRawValue();
    sink.writeInt(value.length);
    sink.write(value);
  }

  /**
   * Simple DumbNode just for testing the {@link UnorderedKeyValuePage}s.
   *
   * @author Sebastian Graf, University of Konstanz
   * @author Johannes Lichtenberger
   *
   */
  public static class DumbNode implements Record {

    /** Node key. */
    private final long mNodeKey;

    /**
     * Simple constructor.
     *
     * @param nodeKey to be set
     * @param pHash to be set
     */
    public DumbNode(final long nodeKey) {
      mNodeKey = nodeKey;
    }

    @Override
    public long getNodeKey() {
      return mNodeKey;
    }

    @Override
    public Kind getKind() {
      return Kind.NULL;
    }

    @Override
    public long getRevision() {
      return 0;
    }
  }
}
