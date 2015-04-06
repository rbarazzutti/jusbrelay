/**
 * Copyright (C) 2015 Raphael P. Barazzutti
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.fever.usbrelay.decttech;


import ch.fever.usbrelay.Controller;
import ch.fever.usbrelay.Relay;
import ch.fever.usbrelay.State;
import ch.fever.usbrelay.UsbRelayNative;
import ch.fever.usbrelay.data.Buffer;
import ch.fever.usbrelay.data.HidDeviceInfoStructure;
import com.sun.jna.Pointer;

import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Driver implements ch.fever.usbrelay.Driver {
    final private UsbRelayNative usn = UsbRelayNative.INSTANCE;


    private class DectController implements Controller {

        final private String identifier;
        final private Pattern pattern = Pattern.compile("^USBRelay(\\d+)$");
        final int nrRelays;

        final private String path;

        protected <T> T apply(Function<Pointer, T> f) {
            Pointer pointer = usn.hid_open_path(path);
            T ret = f.apply(pointer);
            usn.hid_close(pointer);
            return ret;
        }

        public DectController(HidDeviceInfoStructure infoStructure) {
            Matcher matcher = pattern.matcher(infoStructure.productString.toString());

            nrRelays = matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;

            path = infoStructure.path;

            identifier = apply(pp ->
            {
                DectStatus dectStatus = new DectStatus();
                usn.hid_get_feature_report(pp, dectStatus, dectStatus.size());
                return dectStatus.getIdentifier();
            });
        }


        public String getIdentifier() {
            return identifier;
        }


        public Relay[] getRelays() {
            DectRelay relays[] = new DectRelay[nrRelays];
            for (byte i = 0; i < nrRelays; i++)
                relays[i] = new DectRelay(i);
            return relays;
        }

        private class DectRelay implements Relay {
            final private byte id;

            private DectRelay(byte id) {
                this.id = id;
            }

            @Override
            public void setState(State state) {
                byte st = (byte) (state == State.ACTIVE ? 0xff : 0xfd);
                Buffer buf = new Buffer(9);
                buf.bytesArray[1] = st;
                buf.bytesArray[2] = (byte) (id + 1);

                apply(p -> usn.hid_write(p, buf, buf.size()));
            }

            @Override
            public State getState() {
                return apply(p ->
                {
                    DectStatus dectStatus = new DectStatus();
                    usn.hid_get_feature_report(p, dectStatus, dectStatus.size());
                    short state = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN) ? dectStatus.state : Short.reverseBytes(dectStatus.state);
                    return ((state >> id) & 1) == 1 ? State.ACTIVE : State.INACTIVE;
                });

            }
        }
    }


    @Override
    public List<Controller> listControllers() {
        List<Controller> list = new LinkedList<>();
        short vendor_id = 0x16c0;
        short product_id = 0x05df;

        HidDeviceInfoStructure penum = usn.hid_enumerate(vendor_id, product_id);
        HidDeviceInfoStructure p = penum;

        while (p != null) {
            list.add(new DectController(p));
            p = p.next;
        }

        if (penum != null)
            usn.hid_free_enumeration(penum.getPointer());

        usn.hid_exit();
        return list;
    }
}