/*
 * Copyright (c) 2006, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * ===========================================================================
 * (c) Copyright IBM Corp. 2022, 2022 All Rights Reserved
 * ===========================================================================
 */

package sun.security.ec;

import java.io.IOException;
import java.math.BigInteger;

import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.Arrays;

import jdk.crypto.jniprovider.NativeCrypto;

import sun.security.util.*;
import sun.security.x509.AlgorithmId;
import sun.security.pkcs.PKCS8Key;

/**
 * Key implementation for EC private keys.
 *
 * ASN.1 syntax for EC private keys from SEC 1 v1.5 (draft):
 *
 * <pre>
 * EXPLICIT TAGS
 *
 * ECPrivateKey ::= SEQUENCE {
 *   version INTEGER { ecPrivkeyVer1(1) } (ecPrivkeyVer1),
 *   privateKey OCTET STRING,
 *   parameters [0] ECDomainParameters {{ SECGCurveNames }} OPTIONAL,
 *   publicKey [1] BIT STRING OPTIONAL
 * }
 * </pre>
 *
 * We currently ignore the optional parameters and publicKey fields. We
 * require that the parameters are encoded as part of the AlgorithmIdentifier,
 * not in the private key structure.
 *
 * @since   1.6
 * @author  Andreas Sterbenz
 */
public final class ECPrivateKeyImpl extends PKCS8Key implements ECPrivateKey {

    private static final long serialVersionUID = 88695385615075129L;
    private static final NativeCrypto nativeCrypto = NativeCrypto.getNativeCrypto();

    private BigInteger s;       // private value
    private byte[] arrayS;      // private value as a little-endian array
    @SuppressWarnings("serial") // Type of field is not Serializable
    private ECParameterSpec params;
    private long nativeECKey;

    /**
     * Construct a key from its encoding. Called by the ECKeyFactory.
     */
    ECPrivateKeyImpl(byte[] encoded) throws InvalidKeyException {
        super(encoded);
        parseKeyBits();
    }

    /**
     * Construct a key from its components. Used by the
     * KeyFactory.
     */
    ECPrivateKeyImpl(BigInteger s, ECParameterSpec params)
            throws InvalidKeyException {
        this.s = s;
        this.params = params;
        makeEncoding(s);

    }

    ECPrivateKeyImpl(byte[] s, ECParameterSpec params)
            throws InvalidKeyException {
        this.arrayS = s.clone();
        this.params = params;
        makeEncoding(s);
    }

    private void makeEncoding(byte[] s) throws InvalidKeyException {
        algid = new AlgorithmId
        (AlgorithmId.EC_oid, ECParameters.getAlgorithmParameters(params));
        try {
            DerOutputStream out = new DerOutputStream();
            out.putInteger(1); // version 1
            byte[] privBytes = s.clone();
            ArrayUtil.reverse(privBytes);
            out.putOctetString(privBytes);
            Arrays.fill(privBytes, (byte)0);
            DerValue val = DerValue.wrap(DerValue.tag_Sequence, out);
            key = val.toByteArray();
            val.clear();
        } catch (IOException exc) {
            // should never occur
            throw new InvalidKeyException(exc);
        }
    }

    private void makeEncoding(BigInteger s) throws InvalidKeyException {
        algid = new AlgorithmId(AlgorithmId.EC_oid,
                ECParameters.getAlgorithmParameters(params));
        try {
            byte[] sArr = s.toByteArray();
            // convert to fixed-length array
            int numOctets = (params.getOrder().bitLength() + 7) / 8;
            byte[] sOctets = new byte[numOctets];
            int inPos = Math.max(sArr.length - sOctets.length, 0);
            int outPos = Math.max(sOctets.length - sArr.length, 0);
            int length = Math.min(sArr.length, sOctets.length);
            System.arraycopy(sArr, inPos, sOctets, outPos, length);
            Arrays.fill(sArr, (byte)0);

            DerOutputStream out = new DerOutputStream();
            out.putInteger(1); // version 1
            out.putOctetString(sOctets);
            Arrays.fill(sOctets, (byte)0);
            DerValue val = DerValue.wrap(DerValue.tag_Sequence, out);
            key = val.toByteArray();
            val.clear();
        } catch (IOException exc) {
            throw new AssertionError("Should not happen", exc);
        }
    }

    // see JCA doc
    public String getAlgorithm() {
        return "EC";
    }

    // see JCA doc
    public BigInteger getS() {
        if (s == null) {
            byte[] arrCopy = arrayS.clone();
            ArrayUtil.reverse(arrCopy);
            s = new BigInteger(1, arrCopy);
            Arrays.fill(arrCopy, (byte)0);
        }
        return s;
    }

    public byte[] getArrayS() {
        if (arrayS == null) {
            arrayS = ECUtil.sArray(getS(), params);
        }
        return arrayS.clone();
    }

    // see JCA doc
    public ECParameterSpec getParams() {
        return params;
    }

    private void parseKeyBits() throws InvalidKeyException {
        try {
            DerInputStream in = new DerInputStream(key);
            DerValue derValue = in.getDerValue();
            if (derValue.tag != DerValue.tag_Sequence) {
                throw new IOException("Not a SEQUENCE");
            }
            DerInputStream data = derValue.data;
            int version = data.getInteger();
            if (version != 1) {
                throw new IOException("Version must be 1");
            }
            byte[] privData = data.getOctetString();
            ArrayUtil.reverse(privData);
            arrayS = privData;
            while (data.available() != 0) {
                DerValue value = data.getDerValue();
                if (value.isContextSpecific((byte) 0)) {
                    // ignore for now
                } else if (value.isContextSpecific((byte) 1)) {
                    // ignore for now
                } else {
                    throw new InvalidKeyException("Unexpected value: " + value);
                }
            }
            AlgorithmParameters algParams = this.algid.getParameters();
            if (algParams == null) {
                throw new InvalidKeyException("EC domain parameters must be "
                    + "encoded in the algorithm identifier");
            }
            params = algParams.getParameterSpec(ECParameterSpec.class);
        } catch (IOException e) {
            throw new InvalidKeyException("Invalid EC private key", e);
        } catch (InvalidParameterSpecException e) {
            throw new InvalidKeyException("Invalid EC private key", e);
        }
    }

    /**
     * Returns true if this key's EC field is an instance of ECFieldF2m.
     * @return true if the field is an instance of ECFieldF2m, false otherwise
     */
    boolean isECFieldF2m() {
        return this.params.getCurve().getField() instanceof ECFieldF2m;
    }

    /**
     * Returns the native EC public key context pointer.
     * @return the native EC public key context pointer or -1 on error
     */
    long getNativePtr() {
        if (nativeECKey == 0x0) {
            synchronized (this) {
                if (nativeECKey == 0x0) {
                    ECPoint generator = this.params.getGenerator();
                    EllipticCurve curve = this.params.getCurve();
                    ECField field = curve.getField();
                    byte[] a = curve.getA().toByteArray();
                    byte[] b = curve.getB().toByteArray();
                    byte[] gx = generator.getAffineX().toByteArray();
                    byte[] gy = generator.getAffineY().toByteArray();
                    byte[] n = this.params.getOrder().toByteArray();
                    byte[] h = BigInteger.valueOf(this.params.getCofactor()).toByteArray();
                    byte[] p = new byte[0];
                    if (field instanceof ECFieldFp) {
                        p = ((ECFieldFp)field).getP().toByteArray();
                        nativeECKey = nativeCrypto.ECEncodeGFp(a, a.length, b, b.length, p, p.length, gx, gx.length, gy, gy.length, n, n.length, h, h.length);
                    } else if (field instanceof ECFieldF2m) {
                        p = ((ECFieldF2m)field).getReductionPolynomial().toByteArray();
                        nativeECKey = nativeCrypto.ECEncodeGF2m(a, a.length, b, b.length, p, p.length, gx, gx.length, gy, gy.length, n, n.length, h, h.length);
                    } else {
                        nativeECKey = -1;
                    }
                    if (nativeECKey != -1) {
                        nativeCrypto.createECKeyCleaner(this, nativeECKey);
                        byte[] value = this.getS().toByteArray();
                        if (nativeCrypto.ECCreatePrivateKey(nativeECKey, value, value.length) == -1) {
                            nativeECKey = -1;
                        }
                    }
                }
            }
        }
        return nativeECKey;
    }
}
