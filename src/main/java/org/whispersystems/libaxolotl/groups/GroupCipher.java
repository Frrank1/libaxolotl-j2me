/**
 * Copyright (C) 2014-2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.whispersystems.libaxolotl.groups;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.whispersystems.curve25519.SecureRandomProvider;
import org.whispersystems.libaxolotl.DuplicateMessageException;
import org.whispersystems.libaxolotl.InvalidKeyException;
import org.whispersystems.libaxolotl.InvalidKeyIdException;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.LegacyMessageException;
import org.whispersystems.libaxolotl.NoSessionException;
import org.whispersystems.libaxolotl.groups.ratchet.SenderChainKey;
import org.whispersystems.libaxolotl.groups.ratchet.SenderMessageKey;
import org.whispersystems.libaxolotl.groups.state.SenderKeyRecord;
import org.whispersystems.libaxolotl.groups.state.SenderKeyState;
import org.whispersystems.libaxolotl.groups.state.SenderKeyStore;
import org.whispersystems.libaxolotl.j2me.AssertionError;
import org.whispersystems.libaxolotl.protocol.SenderKeyMessage;

import java.io.IOException;

/**
 * The main entry point for axolotl group encrypt/decrypt operations.
 *
 * Once a session has been established with {@link org.whispersystems.libaxolotl.groups.GroupSessionBuilder}
 * and a {@link org.whispersystems.libaxolotl.protocol.SenderKeyDistributionMessage} has been
 * distributed to each member of the group, this class can be used for all subsequent encrypt/decrypt
 * operations within that session (ie: until group membership changes).
 *
 * @author Moxie Marlinspike
 */
public class GroupCipher {

  static final Object LOCK = new Object();

  private final SecureRandomProvider secureRandomProvider;
  private final SenderKeyStore       senderKeyStore;
  private final SenderKeyName        senderKeyId;

  public GroupCipher(SecureRandomProvider secureRandomProvider, SenderKeyStore senderKeyStore, SenderKeyName senderKeyId) {
    this.secureRandomProvider = secureRandomProvider;
    this.senderKeyStore       = senderKeyStore;
    this.senderKeyId          = senderKeyId;
  }

  /**
   * Encrypt a message.
   *
   * @param paddedPlaintext The plaintext message bytes, optionally padded.
   * @return Ciphertext.
   * @throws NoSessionException
   */
  public byte[] encrypt(byte[] paddedPlaintext) throws NoSessionException {
    synchronized (LOCK) {
      try {
        SenderKeyRecord  record         = senderKeyStore.loadSenderKey(senderKeyId);
        SenderKeyState   senderKeyState = record.getSenderKeyState();
        SenderMessageKey senderKey      = senderKeyState.getSenderChainKey().getSenderMessageKey();
        byte[]           ciphertext     = getCipherText(senderKey.getIv(), senderKey.getCipherKey(), paddedPlaintext);
        SenderKeyMessage senderKeyMessage = new SenderKeyMessage(secureRandomProvider,
                                                                 senderKeyState.getKeyId(),
                                                                 senderKey.getIteration(),
                                                                 ciphertext,
                                                                 senderKeyState.getSigningKeyPrivate());

        senderKeyState.setSenderChainKey(senderKeyState.getSenderChainKey().getNext());

        senderKeyStore.storeSenderKey(senderKeyId, record);

        return senderKeyMessage.serialize();
      } catch (InvalidKeyIdException e) {
        throw new NoSessionException(e);
      }
    }
  }

  public byte[] decrypt(byte[] senderKeyMessageBytes)
      throws LegacyMessageException, InvalidMessageException, DuplicateMessageException
  {
    synchronized (LOCK) {
      try {
        SenderKeyRecord  record           = senderKeyStore.loadSenderKey(senderKeyId);
        SenderKeyMessage senderKeyMessage = new SenderKeyMessage(senderKeyMessageBytes);
        SenderKeyState   senderKeyState   = record.getSenderKeyState(senderKeyMessage.getKeyId());

        senderKeyMessage.verifySignature(senderKeyState.getSigningKeyPublic());

        SenderMessageKey senderKey = getSenderKey(senderKeyState, senderKeyMessage.getIteration());
        byte[] plaintext = getPlainText(senderKey.getIv(), senderKey.getCipherKey(), senderKeyMessage.getCipherText());

        senderKeyStore.storeSenderKey(senderKeyId, record);

        return plaintext;
      } catch (InvalidKeyException ike) {
        throw new InvalidMessageException(ike);
      } catch (InvalidKeyIdException ikie) {
        throw new InvalidMessageException(ikie);
      }
    }
  }

  private SenderMessageKey getSenderKey(SenderKeyState senderKeyState, int iteration)
      throws DuplicateMessageException, InvalidMessageException
  {
    SenderChainKey senderChainKey = senderKeyState.getSenderChainKey();

    if (senderChainKey.getIteration() > iteration) {
      if (senderKeyState.hasSenderMessageKey(iteration)) {
        return senderKeyState.removeSenderMessageKey(iteration);
      } else {
        throw new DuplicateMessageException("Received message with old counter: " +
                                            senderChainKey.getIteration() + " , " + iteration);
      }
    }

    if (senderChainKey.getIteration() - iteration > 2000) {
      throw new InvalidMessageException("Over 2000 messages into the future!");
    }

    while (senderChainKey.getIteration() < iteration) {
      senderKeyState.addSenderMessageKey(senderChainKey.getSenderMessageKey());
      senderChainKey = senderChainKey.getNext();
    }

    senderKeyState.setSenderChainKey(senderChainKey.getNext());
    return senderChainKey.getSenderMessageKey();
  }

  private byte[] getPlainText(byte[] iv, byte[] key, byte[] ciphertext)
      throws InvalidMessageException
  {
    try {
      return cipher(false, iv, key, ciphertext);
    } catch (InvalidCipherTextException e) {
      throw new InvalidMessageException(e);
    } catch (IOException e) {
      throw new InvalidMessageException(e);
    }
  }

  private byte[] getCipherText(byte[] iv, byte[] key, byte[] plaintext) {
    try {
      return cipher(true, iv, key, plaintext);
    } catch (InvalidCipherTextException e) {
      throw new AssertionError(e);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private byte[] cipher(boolean encrypt, byte[] iv, byte[] key, byte[] input)
      throws InvalidCipherTextException, IOException
  {
    KeyParameter              keyParameter = new KeyParameter(key, 0, key.length);
    PaddedBufferedBlockCipher cipher       = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()));
    cipher.init(encrypt, new ParametersWithIV(keyParameter, iv, 0, iv.length));

    byte[] buffer    = new byte[cipher.getOutputSize(input.length)];
    int    processed = cipher.processBytes(input, 0, input.length, buffer, 0);
    int    finished  = cipher.doFinal(buffer, processed);

    if (processed + finished < buffer.length) {
      byte[] trimmed = new byte[processed + finished];
      System.arraycopy(buffer, 0, trimmed, 0, trimmed.length);
      return trimmed;
    } else {
      return buffer;
    }
  }

}
