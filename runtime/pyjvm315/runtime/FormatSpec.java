// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

import static pyjvm315.runtime.PyRuntime.*;
import java.math.BigInteger;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

final class FormatSpec {
    final String source; final int[] chars; int pos=0,width=0,precision=-1;
    String fill=" ";char align=0,sign=0,type=0,group=0;boolean alternate=false,zero=false,coerceZero=false,explicitFill=false;
    FormatSpec(String spec){
        source=spec;chars=spec.codePoints().toArray();
        if(chars.length>=2 && isAlign(chars[1])){fill=new String(Character.toChars(chars[0]));align=(char)chars[1];pos=2;explicitFill=true;}
        else if(pos<chars.length && isAlign(chars[pos]))align=(char)chars[pos++];
        if(pos<chars.length && "+- ".indexOf(chars[pos])>=0)sign=(char)chars[pos++];
        if(pos<chars.length && chars[pos]=='z'){coerceZero=true;pos++;}
        if(pos<chars.length && chars[pos]=='#'){alternate=true;pos++;}
        if(pos<chars.length && chars[pos]=='0'){zero=true;pos++;}
        width=digits();
        if(pos<chars.length && (chars[pos]==',' || chars[pos]=='_'))group=(char)chars[pos++];
        if(pos<chars.length && chars[pos]=='.'){
            pos++;if(pos>=chars.length || chars[pos]<'0' || chars[pos]>'9')throw new PyException("ValueError","Format specifier missing precision");
            precision=digits();
        }
        if(pos<chars.length)type=chars[pos]<128?(char)chars[pos++]:0;
        if(pos!=chars.length || type==0 && pos<chars.length)throw new PyException("ValueError","Invalid format specifier");
    }
    static boolean isAlign(int code){return code=='<' || code=='>' || code=='^' || code=='=';}
    int digits(){
        int number=0;
        while(pos<chars.length && chars[pos]>='0' && chars[pos]<='9'){
            int digit=chars[pos++]-'0';if(number>(Integer.MAX_VALUE-digit)/10)throw new PyException("ValueError","Too many decimal digits in format string");
            number=number*10+digit;
        }
        return number;
    }
    String apply(Object value){
        if(source.isEmpty())return value instanceof Double number?shortestFloat(number):pyStr(value);
        String prefix="",body;boolean numeric=!(value instanceof String),integer=isIntLike(value);
        char alignment=align==0?(numeric?'>':'<'):align;
        if(zero && !explicitFill){fill="0";if(align==0 && numeric)alignment='=';}
        if(value instanceof String text){
            if(sign!=0 || alternate || coerceZero || alignment=='=' || group!=0)throw new PyException("ValueError","Invalid format specifier for str");
            if(type!=0 && type!='s')throw new PyException("ValueError","Unknown format code '"+type+"' for object of type 'str'");
            body=precision<0 || precision>=text.codePointCount(0,text.length())?text:text.substring(0,text.offsetByCodePoints(0,precision));
        }else if(integer && (type==0 || "bdoxXnc".indexOf(type)>=0)){
            if(precision>=0)throw new PyException("ValueError","Precision not allowed in integer format specifier");
            if(coerceZero)throw new PyException("ValueError","Negative zero coercion (z) not allowed in integer format specifier");
            BigInteger number=bigInt(value);char kind=type==0?'d':type;
            if(kind=='c'){
                if(sign!=0 || alternate || group!=0)throw new PyException("ValueError","Invalid format specifier for integer character");
                if(number.signum()<0 || number.compareTo(BigInteger.valueOf(0x10ffff))>0)throw new PyException("OverflowError","%c arg not in range(0x110000)");
                body=new String(Character.toChars(number.intValue()));
            }else{
                int radix=kind=='b'?2:kind=='o'?8:kind=='x'||kind=='X'?16:10;
                if(group==',' && radix!=10 || group!=0 && kind=='n')throw new PyException("ValueError","Cannot specify grouping with this format code");
                prefix=number.signum()<0?"-":sign=='+'?"+":sign==' '?" ":"";
                if(alternate && radix!=10)prefix+=radix==2?"0b":radix==8?"0o":kind=='X'?"0X":"0x";
                body=number.abs().toString(radix);if(kind=='X')body=body.toUpperCase(Locale.ROOT);
                if(group!=0){
                    int size=radix==10?3:4;
                    if(alignment=='=' && fill.equals("0"))while(prefix.length()+body.length()+(body.length()-1)/size<width)body="0"+body;
                    body=groupDigits(body,size,group);
                }
            }
        }else{
            if(type!=0 && "eEfFgGn%".indexOf(type)<0)throw new PyException("ValueError","Unknown format code '"+type+"' for object of type '"+typeName(value)+"'");
            if(group!=0 && type=='n')throw new PyException("ValueError","Cannot specify grouping with 'n'");
            double number=percentFloat(value,'f');boolean negative=Double.doubleToRawLongBits(number)<0 && !Double.isNaN(number);
            double magnitude=Math.abs(number);char kind=type=='n'?'g':type;
            if(kind=='%'){magnitude*=100;kind='f';}
            if(kind==0 && precision<0){body=shortestFloat(magnitude);if(alternate && body.indexOf('.')<0 && Double.isFinite(magnitude)){int e=body.indexOf('e');body=e<0?body+".":body.substring(0,e)+"."+body.substring(e);}}
            else{
                body=floatDigits(magnitude,kind==0?'g':kind,precision,alternate);
                if(kind==0 && Double.isFinite(magnitude)){
                    int count=Math.max(1,precision);
                    java.math.BigDecimal rounded=new java.math.BigDecimal(magnitude).round(new java.math.MathContext(count,java.math.RoundingMode.HALF_EVEN));
                    int exponent=rounded.signum()==0?0:rounded.precision()-rounded.scale()-1;
                    if(exponent>=count-1){body=floatDigits(magnitude,'e',count-1,alternate);if(!alternate){int e=body.indexOf('e');body=trimFraction(body.substring(0,e))+body.substring(e);}}
                    else if(body.indexOf('.')<0 && body.indexOf('e')<0)body+=".0";
                }
            }
            if(coerceZero && Double.isFinite(magnitude)){
                String numericBody=body;int e=numericBody.indexOf('e');if(e<0)e=numericBody.indexOf('E');
                if(e>=0)numericBody=numericBody.substring(0,e);
                if(new java.math.BigDecimal(numericBody).signum()==0)negative=false;
            }
            prefix=negative?"-":sign=='+'?"+":sign==' '?" ":"";
            if(group!=0){int end=body.indexOf('.');if(end<0)end=body.indexOf('e');if(end<0)end=body.indexOf('E');if(end<0)end=body.length();
                if(Double.isFinite(magnitude)){
                    String digits=body.substring(0,end),rest=body.substring(end)+(type=='%'?"%":"");
                    if(alignment=='=' && fill.equals("0"))while(prefix.length()+digits.length()+(digits.length()-1)/3+rest.length()<width)digits="0"+digits;
                    body=groupDigits(digits,3,group)+body.substring(end);
                }
            }
            if(type=='%')body+="%";
        }
        int padding=Math.max(0,width-prefix.length()-body.codePointCount(0,body.length()));
        return switch(alignment){
            case '<' -> prefix+body+fill.repeat(padding);
            case '^' -> fill.repeat(padding/2)+prefix+body+fill.repeat(padding-padding/2);
            case '=' -> prefix+fill.repeat(padding)+body;
            default -> fill.repeat(padding)+prefix+body;
        };
    }
}
