/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.llm

import org.moqui.impl.entity.EntityFacadeImpl

/** Reaches the field encryption of the entity engine, which is package-private there, without copying its algorithm. */
final class EntityJavaUtilBridge {
    private EntityJavaUtilBridge() { }
    static String encrypt(EntityFacadeImpl efi, String value) { org.moqui.impl.entity.EntityJavaUtil.enDeCrypt(value, true, efi) }
    static String decrypt(EntityFacadeImpl efi, String value) { org.moqui.impl.entity.EntityJavaUtil.enDeCrypt(value, false, efi) }
}
