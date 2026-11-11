// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.NativeTypes.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code _opcode} extension module. pyJVM runs on the JVM and has no CPython bytecode, so the module answers from
 * the per-version opcode tables of the CPython being emulated ({@code LangVersion}): validity, the {@code has_*}
 * classification, the stack effects, and the intrinsic / binary-operator / special-method name tables that
 * {@code opcode.py} and {@code dis.py} import. Each table row reads
 * {@code opcode:flags:effect(None);effect(jump=True);effect(jump=False)} where an effect is {@code a,b,c} for
 * {@code a*oparg + b + c*(oparg&1)}, {@code U} for UNPACK_EX, {@code S} for BUILD_SLICE or {@code X} for "invalid".
 * What cannot be answered here: there are no executors (no JIT), so {@code get_executor} always returns None, and
 * specialization is off.
 */
final class OpcodeModule {
    private OpcodeModule() {}

    private static final String ME = OpcodeModule.class.getName();

    private static final String D313 = "0:0:0,0,0;0,0,0;0,0,0|1:0:0,1,0;0,1,0;0,1,0|2:0:0,1,0;0,1,0;0,1,0|3:32:X;X;X|4:0:0,-2,0;0,-2,0;0,-2,0|5:0:0,-1,0;0,-1,0;0,-1,0|6:0:0,0,0;0,0,0;0,0,0|7:0:0,0,0;0,0,0;0,0,0|8:0:0,-1,0;0,-1,0;0,-1,0|9:0:0,-2,0;0,-2,0;0,-2,0|10:0:0,-2,0;0,-2,0;0,-2,0|11:0:0,-1,0;0,-1,0;0,-1,0|12:0:0,-1,0;0,-1,0;0,-1,0|13:0:0,-1,0;0,-1,0;0,-1,0|14:0:0,0,0;0,0,0;0,0,0|15:0:0,-1,0;0,-1,0;0,-1,0|16:0:0,0,0;0,0,0;0,0,0|17:0:0,0,0;0,0,0;0,0,0|18:0:0,1,0;0,1,0;0,1,0|19:0:0,0,0;0,0,0;0,0,0|20:0:0,1,0;0,1,0;0,1,0|21:0:0,0,0;0,0,0;0,0,0|22:0:0,-1,0;0,-1,0;0,-1,0|23:0:0,1,0;0,1,0;0,1,0|24:0:0,1,0;0,1,0;0,1,0|25:0:0,1,0;0,1,0;0,1,0|26:0:0,0,0;0,0,0;0,0,0|27:0:0,1,0;0,1,0;0,1,0|28:0:0,1,0;0,1,0;0,1,0|29:0:0,1,0;0,1,0;0,1,0|30:0:0,0,0;0,0,0;0,0,0|31:0:0,-1,0;0,-1,0;0,-1,0|32:0:0,-1,0;0,-1,0;0,-1,0|33:0:0,1,0;0,1,0;0,1,0|34:0:0,1,0;0,1,0;0,1,0|35:0:0,1,0;0,1,0;0,1,0|36:0:0,-1,0;0,-1,0;0,-1,0|37:0:0,0,0;0,0,0;0,0,0|38:0:0,-4,0;0,-4,0;0,-4,0|39:0:0,-3,0;0,-3,0;0,-3,0|40:0:0,0,0;0,0,0;0,0,0|41:0:0,0,0;0,0,0;0,0,0|42:0:0,0,0;0,0,0;0,0,0|43:0:0,0,0;0,0,0;0,0,0|44:0:0,1,0;0,1,0;0,1,0|45:1:0,-1,0;0,-1,0;0,-1,0|46:1:-1,0,0;-1,0,0;-1,0,0|47:1:-1,1,0;-1,1,0;-1,1,0|48:1:-2,1,0;-2,1,0;-2,1,0|49:1:-1,1,0;-1,1,0;-1,1,0|50:1:S;S;S|51:1:-1,1,0;-1,1,0;-1,1,0|52:1:-1,1,0;-1,1,0;-1,1,0|53:1:-1,-1,0;-1,-1,0;-1,-1,0|54:1:0,-2,-1;0,-2,-1;0,-2,-1|55:1:0,0,0;0,0,0;0,0,0|56:1:0,-1,0;0,-1,0;0,-1,0|57:1:-1,-2,0;-1,-2,0;-1,-2,0|58:1:0,-1,0;0,-1,0;0,-1,0|59:1:0,-1,0;0,-1,0;0,-1,0|60:1:0,0,0;0,0,0;0,0,0|61:1:0,1,0;0,1,0;0,1,0|62:1:0,0,0;0,0,0;0,0,0|63:5:0,-1,0;0,-1,0;0,-1,0|64:17:0,0,0;0,0,0;0,0,0|65:33:0,0,0;0,0,0;0,0,0|66:5:0,0,0;0,0,0;0,0,0|67:5:0,0,0;0,0,0;0,0,0|68:1:0,-1,0;0,-1,0;0,-1,0|69:1:0,-1,0;0,-1,0;0,-1,0|70:1:0,0,0;0,0,0;0,0,0|71:1:0,0,0;0,0,0;0,0,0|72:9:0,1,0;0,1,0;0,1,0|73:1:0,0,0;0,0,0;0,0,0|74:5:0,1,0;0,1,0;0,1,0|75:5:0,-1,0;0,-1,0;0,-1,0|76:1:0,-1,0;0,-1,0;0,-1,0|77:9:0,0,0;0,0,0;0,0,0|78:9:0,0,0;0,0,0;0,0,0|79:9:0,0,0;0,0,0;0,0,0|80:1:0,-1,0;0,-1,0;0,-1,0|81:1:0,-1,0;0,-1,0;0,-1,0|82:5:0,0,1;0,0,1;0,0,1|83:3:0,1,0;0,1,0;0,1,0|84:17:0,1,0;0,1,0;0,1,0|85:33:0,1,0;0,1,0;0,1,0|86:33:0,1,0;0,1,0;0,1,0|87:33:0,1,0;0,1,0;0,1,0|88:33:0,2,0;0,2,0;0,2,0|89:17:0,0,0;0,0,0;0,0,0|90:5:0,0,0;0,0,0;0,0,0|91:5:0,1,1;0,1,1;0,1,1|92:5:0,1,0;0,1,0;0,1,0|93:5:0,-2,1;0,-2,1;0,-2,1|94:17:0,0,0;0,0,0;0,0,0|95:1:0,-2,0;0,-2,0;0,-2,0|96:1:0,-2,0;0,-2,0;0,-2,0|97:9:0,-1,0;0,-1,0;0,-1,0|98:9:0,-1,0;0,-1,0;0,-1,0|99:9:0,-1,0;0,-1,0;0,-1,0|100:9:0,-1,0;0,-1,0;0,-1,0|101:1:-1,0,0;-1,0,0;-1,0,0|102:1:0,-1,0;0,-1,0;0,-1,0|103:3:0,0,0;0,0,0;0,0,0|104:9:0,0,0;0,0,0;0,0,0|105:1:0,-1,0;0,-1,0;0,-1,0|106:1:0,-1,0;0,-1,0;0,-1,0|107:1:0,-1,0;0,-1,0;0,-1,0|108:5:0,-2,0;0,-2,0;0,-2,0|109:17:0,-1,0;0,-1,0;0,-1,0|110:33:0,-1,0;0,-1,0;0,-1,0|111:33:0,0,0;0,0,0;0,0,0|112:33:0,-2,0;0,-2,0;0,-2,0|113:5:0,-1,0;0,-1,0;0,-1,0|114:5:0,-1,0;0,-1,0;0,-1,0|115:1:0,0,0;0,0,0;0,0,0|116:1:U;U;U|117:1:1,-1,0;1,-1,0;1,-1,0|118:1:0,0,0;0,0,0;0,0,0|149:1:0,0,0;0,0,0;0,0,0|150:0:X;X;X|151:0:X;X;X|152:0:X;X;X|153:0:X;X;X|154:0:X;X;X|155:0:X;X;X|156:0:X;X;X|157:0:X;X;X|158:0:X;X;X|159:0:X;X;X|160:0:X;X;X|161:0:X;X;X|162:1:X;X;X|163:1:X;X;X|164:1:X;X;X|165:1:X;X;X|166:1:X;X;X|167:1:X;X;X|168:1:X;X;X|169:1:X;X;X|170:1:X;X;X|171:1:X;X;X|172:1:X;X;X|173:1:X;X;X|174:1:X;X;X|175:1:X;X;X|176:1:X;X;X|177:1:X;X;X|178:1:X;X;X|179:1:X;X;X|180:1:X;X;X|181:1:X;X;X|182:1:X;X;X|183:1:X;X;X|184:1:X;X;X|185:1:X;X;X|186:1:X;X;X|187:1:X;X;X|188:9:X;X;X|189:9:X;X;X|190:9:X;X;X|191:1:X;X;X|192:5:X;X;X|193:1:X;X;X|194:1:X;X;X|195:1:X;X;X|196:1:X;X;X|197:1:X;X;X|198:1:X;X;X|199:1:X;X;X|200:1:X;X;X|201:1:X;X;X|202:5:X;X;X|203:1:X;X;X|204:1:X;X;X|205:5:X;X;X|206:5:X;X;X|207:0:X;X;X|208:1:X;X;X|209:0:X;X;X|210:0:X;X;X|211:5:X;X;X|212:0:X;X;X|213:0:X;X;X|214:0:X;X;X|215:0:X;X;X|216:0:X;X;X|217:0:X;X;X|218:0:X;X;X|219:0:X;X;X|220:1:X;X;X|221:1:X;X;X|222:1:X;X;X|236:1:0,0,0;0,0,0;0,0,0|237:0:0,-1,0;0,-1,0;0,-1,0|238:0:0,-1,0;0,-1,0;0,-1,0|239:0:0,-1,0;0,-1,0;0,-1,0|240:3:0,0,0;0,0,0;0,0,0|241:1:0,0,0;0,0,0;0,0,0|242:1:0,-2,1;0,-2,1;0,-2,1|243:1:0,0,0;0,0,0;0,0,0|244:1:0,0,0;0,0,0;0,0,0|245:1:0,0,0;0,0,0;0,0,0|246:0:0,0,0;0,0,0;0,0,0|247:0:0,0,0;0,0,0;0,0,0|248:1:0,0,0;0,0,0;0,0,0|249:1:0,0,0;0,0,0;0,0,0|250:1:0,0,0;0,0,0;0,0,0|251:1:0,0,0;0,0,0;0,0,0|252:1:0,0,0;0,0,0;0,0,0|253:1:0,0,0;0,0,0;0,0,0|256:9:0,0,0;0,0,0;0,0,0|257:9:0,0,0;0,0,0;0,0,0|258:33:0,1,0;0,1,0;0,1,0|259:5:0,1,0;0,1,0;0,1,0|260:5:0,-1,0;0,-1,0;0,-1,0|261:5:0,-1,0;0,-1,0;0,-1,0|262:5:0,-1,0;0,-1,0;0,-1,0|263:0:0,0,0;0,0,0;0,0,0|264:65:0,2,0;0,2,0;0,0,0|265:65:0,1,0;0,1,0;0,0,0|266:65:0,1,0;0,1,0;0,0,0|267:33:0,-1,0;0,-1,0;0,-1,0";
    private static final String D315 = "0:0:0,0,0;0,0,0;0,0,0|1:0:0,-2,0;0,-2,0;0,-2,0|2:0:0,-1,0;0,-1,0;0,-1,0|3:32:X;X;X|4:0:0,-3,0;0,-3,0;0,-3,0|5:0:0,0,0;0,0,0;0,0,0|6:0:0,0,0;0,0,0;0,0,0|7:0:0,-1,0;0,-1,0;0,-1,0|8:0:0,-2,0;0,-2,0;0,-2,0|9:0:0,-1,0;0,-1,0;0,-1,0|10:0:0,-2,0;0,-2,0;0,-2,0|11:0:0,-1,0;0,-1,0;0,-1,0|12:0:0,0,0;0,0,0;0,0,0|13:0:0,-1,0;0,-1,0;0,-1,0|14:0:0,0,0;0,0,0;0,0,0|15:0:0,1,0;0,1,0;0,1,0|16:0:0,1,0;0,1,0;0,1,0|17:0:0,0,0;0,0,0;0,0,0|18:0:0,-1,0;0,-1,0;0,-1,0|19:0:0,1,0;0,1,0;0,1,0|20:0:0,1,0;0,1,0;0,1,0|21:0:0,0,0;0,0,0;0,0,0|22:0:0,1,0;0,1,0;0,1,0|23:0:0,1,0;0,1,0;0,1,0|24:0:0,1,0;0,1,0;0,1,0|25:0:0,0,0;0,0,0;0,0,0|26:0:0,0,0;0,0,0;0,0,0|27:0:0,-1,0;0,-1,0;0,-1,0|28:0:0,-2,0;0,-2,0;0,-2,0|29:0:0,-1,0;0,-1,0;0,-1,0|30:0:0,1,0;0,1,0;0,1,0|31:0:0,1,0;0,1,0;0,1,0|32:0:0,1,0;0,1,0;0,1,0|33:0:0,0,0;0,0,0;0,0,0|34:0:0,0,0;0,0,0;0,0,0|35:0:0,-4,0;0,-4,0;0,-4,0|36:0:0,-3,0;0,-3,0;0,-3,0|37:0:0,0,0;0,0,0;0,0,0|38:0:0,0,0;0,0,0;0,0,0|39:0:0,0,0;0,0,0;0,0,0|40:0:0,0,0;0,0,0;0,0,0|41:0:0,1,0;0,1,0;0,1,0|42:1:0,-1,0;0,-1,0;0,-1,0|43:1:0,-1,-1;0,-1,-1;0,-1,-1|44:1:-1,1,0;-1,1,0;-1,1,0|45:1:-2,1,0;-2,1,0;-2,1,0|46:1:-1,1,0;-1,1,0;-1,1,0|47:1:-1,1,0;-1,1,0;-1,1,0|48:1:-1,1,0;-1,1,0;-1,1,0|49:1:-1,1,0;-1,1,0;-1,1,0|50:1:-1,-1,0;-1,-1,0;-1,-1,0|51:1:0,0,0;0,0,0;0,0,0|52:1:0,-1,0;0,-1,0;0,-1,0|53:1:-1,-2,0;-1,-2,0;-1,-2,0|54:1:0,-1,0;0,-1,0;0,-1,0|55:1:0,-1,0;0,-1,0;0,-1,0|56:1:0,0,0;0,0,0;0,0,0|57:1:0,1,0;0,1,0;0,1,0|58:1:0,0,0;0,0,0;0,0,0|59:5:0,-1,0;0,-1,0;0,-1,0|60:17:0,0,0;0,0,0;0,0,0|61:33:0,0,0;0,0,0;0,0,0|62:5:0,0,0;0,0,0;0,0,0|63:5:0,0,0;0,0,0;0,0,0|64:1:0,-1,0;0,-1,0;0,-1,0|65:1:0,-1,0;0,-1,0;0,-1,0|66:9:0,-2,0;0,-2,0;0,-2,0|67:1:0,0,0;0,0,0;0,0,0|68:9:0,1,0;0,1,0;0,1,0|69:1:0,0,0;0,0,0;0,0,0|70:1:0,1,0;0,1,0;0,1,0|71:5:0,1,0;0,1,0;0,1,0|72:5:0,-1,0;0,-1,0;0,-1,0|73:1:0,-1,0;0,-1,0;0,-1,0|74:9:0,0,0;0,0,0;0,0,0|75:9:0,0,0;0,0,0;0,0,0|76:9:0,0,0;0,0,0;0,0,0|77:1:0,-1,0;0,-1,0;0,-1,0|78:1:0,-1,0;0,-1,0;0,-1,0|79:5:0,0,1;0,0,1;0,0,1|80:1:0,1,0;0,1,0;0,1,0|81:3:0,1,0;0,1,0;0,1,0|82:33:0,1,0;0,1,0;0,1,0|83:33:0,1,0;0,1,0;0,1,0|84:33:0,1,0;0,1,0;0,1,0|85:33:0,1,0;0,1,0;0,1,0|86:33:0,2,0;0,2,0;0,2,0|87:33:0,1,0;0,1,0;0,1,0|88:33:0,2,0;0,2,0;0,2,0|89:17:0,0,0;0,0,0;0,0,0|90:5:0,0,0;0,0,0;0,0,0|91:5:0,1,1;0,1,1;0,1,1|92:5:0,1,0;0,1,0;0,1,0|93:1:0,1,0;0,1,0;0,1,0|94:1:0,1,0;0,1,0;0,1,0|95:5:0,-2,1;0,-2,1;0,-2,1|96:17:0,0,0;0,0,0;0,0,0|97:1:0,-2,0;0,-2,0;0,-2,0|98:1:0,-2,0;0,-2,0;0,-2,0|99:9:0,-1,0;0,-1,0;0,-1,0|100:9:0,-1,0;0,-1,0;0,-1,0|101:9:0,-1,0;0,-1,0;0,-1,0|102:9:0,-1,0;0,-1,0;0,-1,0|103:1:-1,0,0;-1,0,0;-1,0,0|104:1:0,-1,0;0,-1,0;0,-1,0|105:9:0,0,0;0,0,0;0,0,0|106:1:0,-1,0;0,-1,0;0,-1,0|107:1:0,-1,0;0,-1,0;0,-1,0|108:1:0,-1,0;0,-1,0;0,-1,0|109:5:0,-2,0;0,-2,0;0,-2,0|110:17:0,-1,0;0,-1,0;0,-1,0|111:33:0,-1,0;0,-1,0;0,-1,0|112:33:0,0,0;0,0,0;0,0,0|113:33:0,-2,0;0,-2,0;0,-2,0|114:5:0,-1,0;0,-1,0;0,-1,0|115:5:0,-1,0;0,-1,0;0,-1,0|116:1:0,0,0;0,0,0;0,0,0|117:1:U;U;U|118:1:1,-1,0;1,-1,0;1,-1,0|119:1:0,0,0;0,0,0;0,0,0|128:1:0,0,0;0,0,0;0,0,0|129:0:X;X;X|130:0:X;X;X|131:0:X;X;X|132:0:X;X;X|133:0:X;X;X|134:0:X;X;X|135:0:X;X;X|136:0:X;X;X|137:0:X;X;X|138:0:X;X;X|139:0:X;X;X|140:0:X;X;X|141:0:X;X;X|142:0:X;X;X|143:0:X;X;X|144:1:X;X;X|145:1:X;X;X|146:1:X;X;X|147:1:X;X;X|148:1:X;X;X|149:1:X;X;X|150:1:X;X;X|151:0:X;X;X|152:1:X;X;X|153:0:X;X;X|154:1:X;X;X|155:1:X;X;X|156:1:X;X;X|157:0:X;X;X|158:1:X;X;X|159:1:X;X;X|160:1:X;X;X|161:1:X;X;X|162:1:X;X;X|163:1:X;X;X|164:1:X;X;X|165:1:X;X;X|166:1:X;X;X|167:1:X;X;X|168:1:X;X;X|169:1:X;X;X|170:1:X;X;X|171:1:X;X;X|172:1:X;X;X|173:1:X;X;X|174:1:X;X;X|175:9:X;X;X|176:9:X;X;X|177:9:X;X;X|178:9:X;X;X|179:0:X;X;X|180:0:X;X;X|181:9:X;X;X|182:9:X;X;X|183:1:X;X;X|184:1:X;X;X|185:5:X;X;X|186:1:X;X;X|187:1:X;X;X|188:1:X;X;X|189:1:X;X;X|190:1:X;X;X|191:1:X;X;X|192:1:X;X;X|193:1:X;X;X|194:1:X;X;X|195:5:X;X;X|196:1:X;X;X|197:1:X;X;X|198:5:X;X;X|199:5:X;X;X|200:0:X;X;X|201:1:X;X;X|202:9:X;X;X|203:1:X;X;X|204:9:X;X;X|205:0:X;X;X|206:0:X;X;X|207:5:X;X;X|208:0:X;X;X|209:0:X;X;X|210:0:X;X;X|211:0:X;X;X|212:0:X;X;X|213:0:X;X;X|214:0:X;X;X|215:0:X;X;X|216:1:X;X;X|217:1:X;X;X|218:1:X;X;X|233:0:0,-1,0;0,-1,0;0,-1,0|234:0:0,-2,0;0,-2,0;0,-2,0|235:0:0,-2,0;0,-2,0;0,-2,0|236:9:0,1,0;0,1,0;0,1,0|237:0:0,0,0;0,0,0;0,0,0|238:1:0,0,0;0,0,0;0,0,0|239:0:0,0,0;0,0,0;0,0,0|240:1:0,-1,0;0,-1,0;0,-1,0|241:1:0,-1,0;0,-1,0;0,-1,0|242:1:0,-1,0;0,-1,0;0,-1,0|243:1:0,-1,0;0,-1,0;0,-1,0|244:1:0,0,0;0,0,0;0,0,0|245:0:0,0,0;0,0,0;0,0,0|246:1:0,0,0;0,0,0;0,0,0|247:9:0,-2,0;0,-2,0;0,-2,0|248:5:0,-2,1;0,-2,1;0,-2,1|249:1:-1,-1,0;-1,-1,0;-1,-1,0|250:1:-1,-2,0;-1,-2,0;-1,-2,0|251:0:0,-3,0;0,-3,0;0,-3,0|252:1:0,0,0;0,0,0;0,0,0|253:0:0,0,0;0,0,0;0,0,0|254:1:0,0,0;0,0,0;0,0,0|255:1:0,0,0;0,0,0;0,0,0|256:0:0,0,0;0,0,0;0,0,0|257:9:0,0,0;0,0,0;0,0,0|258:9:0,0,0;0,0,0;0,0,0|259:9:0,0,0;0,0,0;0,0,0|260:9:0,0,0;0,0,0;0,0,0|261:33:0,1,0;0,1,0;0,1,0|262:0:0,0,0;0,0,0;0,0,0|263:65:0,2,0;0,2,0;0,0,0|264:65:0,1,0;0,1,0;0,0,0|265:65:0,1,0;0,1,0;0,0,0|266:33:0,-1,0;0,-1,0;0,-1,0";

    private static final String[] FLAG_NAMES = {"has_arg", "has_const", "has_name", "has_jump", "has_free", "has_local", "has_exc"};

    private static final String[] INTRINSIC1 = {"INTRINSIC_1_INVALID", "INTRINSIC_PRINT", "INTRINSIC_IMPORT_STAR", "INTRINSIC_STOPITERATION_ERROR",
        "INTRINSIC_ASYNC_GEN_WRAP", "INTRINSIC_UNARY_POSITIVE", "INTRINSIC_LIST_TO_TUPLE", "INTRINSIC_TYPEVAR", "INTRINSIC_PARAMSPEC",
        "INTRINSIC_TYPEVARTUPLE", "INTRINSIC_SUBSCRIPT_GENERIC", "INTRINSIC_TYPEALIAS"};
    private static final String[] INTRINSIC2 = {"INTRINSIC_2_INVALID", "INTRINSIC_PREP_RERAISE_STAR", "INTRINSIC_TYPEVAR_WITH_BOUND",
        "INTRINSIC_TYPEVAR_WITH_CONSTRAINTS", "INTRINSIC_SET_FUNCTION_TYPE_PARAMS", "INTRINSIC_SET_TYPEPARAM_DEFAULT"};
    private static final String[][] NB_OPS = {{"NB_ADD", "+"}, {"NB_AND", "&"}, {"NB_FLOOR_DIVIDE", "//"}, {"NB_LSHIFT", "<<"},
        {"NB_MATRIX_MULTIPLY", "@"}, {"NB_MULTIPLY", "*"}, {"NB_REMAINDER", "%"}, {"NB_OR", "|"}, {"NB_POWER", "**"}, {"NB_RSHIFT", ">>"},
        {"NB_SUBTRACT", "-"}, {"NB_TRUE_DIVIDE", "/"}, {"NB_XOR", "^"}, {"NB_INPLACE_ADD", "+="}, {"NB_INPLACE_AND", "&="},
        {"NB_INPLACE_FLOOR_DIVIDE", "//="}, {"NB_INPLACE_LSHIFT", "<<="}, {"NB_INPLACE_MATRIX_MULTIPLY", "@="}, {"NB_INPLACE_MULTIPLY", "*="},
        {"NB_INPLACE_REMAINDER", "%="}, {"NB_INPLACE_OR", "|="}, {"NB_INPLACE_POWER", "**="}, {"NB_INPLACE_RSHIFT", ">>="},
        {"NB_INPLACE_SUBTRACT", "-="}, {"NB_INPLACE_TRUE_DIVIDE", "/="}, {"NB_INPLACE_XOR", "^="}, {"NB_SUBSCR", "[]"}};
    private static final String[] SPECIAL_METHODS = {"__enter__", "__exit__", "__aenter__", "__aexit__"};

    /** Parsed table of the active version: flags and effect rows by opcode, null entries for invalid opcodes. */
    private static final class Table {
        final int[] flags = new int[1024];
        final boolean[] valid = new boolean[1024];
        final String[][] effects = new String[1024][];
    }

    private static Table table;

    private static synchronized Table table() {
        if (table == null) {
            Table t = new Table();
            for (String row : (LangVersion.atLeast(3, 15) ? D315 : D313).split("\\|")) {
                String[] p = row.split(":");
                int op = Integer.parseInt(p[0]);
                t.valid[op] = true;
                t.flags[op] = Integer.parseInt(p[1]);
                t.effects[op] = p[2].split(";");
            }
            table = t;
        }
        return table;
    }

    static synchronized PyModule create() {
        PyModule m = new PyModule("_opcode", ME);
        m.attrs.put("__doc__", "Opcode support module.");
        m.attrs.put("__name__", "_opcode");
        m.attrs.put("ENABLE_SPECIALIZATION", 0L);
        fn(m, "stack_effect", (a, k) -> stackEffect(a, k));
        fn(m, "is_valid", (a, k) -> classify("is_valid", -1, a, k));
        for (int i = 0; i < FLAG_NAMES.length; i++) {
            final String name = FLAG_NAMES[i];
            final int bit = 1 << i;
            fn(m, name, (a, k) -> classify(name, bit, a, k));
        }
        fn(m, "get_intrinsic1_descs", (a, k) -> names(INTRINSIC1));
        fn(m, "get_intrinsic2_descs", (a, k) -> names(INTRINSIC2));
        fn(m, "get_nb_ops", (a, k) -> {
            ArrayList<Object> out = new ArrayList<>();
            for (int i = 0; i < (LangVersion.atLeast(3, 14) ? NB_OPS.length : NB_OPS.length - 1); i++) {
                String[] p = NB_OPS[i];
                PyTuple t = new PyTuple();
                t.items.add(p[0]);
                t.items.add(p[1]);
                out.add(t);
            }
            return out;
        });
        if (LangVersion.atLeast(3, 15)) fn(m, "get_special_method_names", (a, k) -> names(SPECIAL_METHODS));
        fn(m, "get_specialization_stats", (a, k) -> null);
        fn(m, "get_executor", (a, k) -> {
            Object[] p = NativeArgs.parse("get_executor", a, k, new String[]{"code", "offset"}, 2, 0);
            if (!"code".equals(PyRuntime.typeName(p[0]))) throw new PyException("TypeError", "expected a code object, not '" + PyRuntime.typeName(p[0]) + "'");
            cInt(p[1]);
            return null;
        });
        return m;
    }

    private static ArrayList<Object> names(String[] src) {
        ArrayList<Object> out = new ArrayList<>();
        for (String s : src) out.add(s);
        return out;
    }

    /** A Python int as a C {@code int} ({@code OverflowError} when it does not fit). */
    private static int cInt(Object o) {
        BigInteger b = BuiltinsExtra.rangeIndex(o);
        if (b.bitLength() > 31) throw new PyException("OverflowError", "Python int too large to convert to C int");
        return b.intValue();
    }

    private static Object classify(String name, int bit, List<Object> a, Map<Object, Object> k) {
        if (!k.isEmpty()) throw new PyException("TypeError", name + "() takes no keyword arguments");
        if (a.isEmpty()) throw new PyException("TypeError", name + "() missing required argument 'opcode' (pos 1)");
        if (a.size() > 1) throw new PyException("TypeError", name + "() takes at most 1 argument (" + a.size() + " given)");
        int op = cInt(a.get(0));
        Table t = table();
        if (op < 0 || op >= t.valid.length || !t.valid[op]) return false;
        return bit < 0 || (t.flags[op] & bit) != 0;
    }

    private static Object stackEffect(List<Object> a, Map<Object, Object> kw) {
        if (a.isEmpty()) throw new PyException("TypeError", "stack_effect() takes at least 1 positional argument (0 given)");
        if (a.size() > 2) throw new PyException("TypeError", "stack_effect() takes at most 2 positional arguments (" + a.size() + " given)");
        Object jump = null;
        for (Map.Entry<Object, Object> e : kw.entrySet()) {
            if (!"jump".equals(e.getKey())) throw new PyException("TypeError", "stack_effect() got an unexpected keyword argument '" + PyRuntime.pyStr(e.getKey()) + "'");
            jump = e.getValue();
        }
        int op = cInt(a.get(0));
        long oparg = 0;
        Object oa = a.size() > 1 ? a.get(1) : null;
        if (oa != null) {
            BigInteger b = BuiltinsExtra.rangeIndex(oa);
            if (b.bitLength() > 63) throw new PyException("OverflowError", "Python int too large to convert to C long");
            oparg = b.longValue();
        }
        int col;
        if (jump == null) col = 0;
        else if (Boolean.TRUE.equals(jump)) col = 1;
        else if (Boolean.FALSE.equals(jump)) col = 2;
        else throw new PyException("ValueError", "stack_effect: jump must be False, True or None");
        Table t = table();
        if (op < 0 || op >= t.valid.length || !t.valid[op]) throw invalid();
        String e = t.effects[op][col];
        if (e.equals("X")) throw invalid();
        long x = (int) oparg;
        if (oparg >= (1L << 31) && oparg < (1L << 32) && !e.startsWith("0,")) throw invalid();
        long v;
        if (e.equals("U")) v = (x & 0xFF) + (x >> 8);
        else if (e.equals("S")) v = x == 3 ? -2 : -1;
        else {
            String[] p = e.split(",");
            v = Long.parseLong(p[0]) * x + Long.parseLong(p[1]) + Long.parseLong(p[2]) * (x & 1);
        }
        if (v != (int) v) throw invalid();
        return v;
    }

    private static PyException invalid() { return new PyException("ValueError", "invalid opcode or oparg"); }
}
