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

final class PercentFormatter {
    final String format; final Object operand; final List<Object> args;
    final boolean tuple,mapping; int index=0,position=0; boolean keyed=false;
    PercentFormatter(String format,Object operand){
        this.format=format;this.operand=operand;tuple=operand instanceof PyTuple;
        args=tuple?((PyTuple)operand).items:Collections.singletonList(operand);
        mapping=operand instanceof Map || operand instanceof List || operand instanceof PyByteSequence || operand instanceof PyRange ||
            operand instanceof PyInstance instance && instance.cls.lookupMethod("__getitem__")!=null;
    }
    Object next(boolean star){
        if(index>=args.size() || star && !tuple)
            throw new PyException("TypeError","not enough arguments for format string (got "+args.size()+")");
        return args.get(index++);
    }
    int star(boolean precision,int start){
        if(keyed)throw new PyException("ValueError","* cannot be used with a parenthesised mapping key at position "+start);
        Object value=next(true);
        if(!isIntLike(value))throw new PyException("TypeError","format argument "+index+": * requires int, not "+typeName(value));
        BigInteger integer=bigInt(value);
        if(precision){
            try{return Math.max(0,integer.intValueExact());}
            catch(ArithmeticException error){throw new PyException("OverflowError","format argument "+index+": too big for precision");}
        }
        // Width uses Py_ssize_t, but Java strings have int-sized storage.
        try{return integer.intValueExact();}
        catch(ArithmeticException error){throw new PyException("OverflowError","format argument "+index+": too big for width");}
    }
    int digits(boolean precision,int start){
        long value=0;
        while(position<format.length() && format.charAt(position)>='0' && format.charAt(position)<='9'){
            int digit=format.charAt(position++)-'0';
            if(value>(Integer.MAX_VALUE-digit)/10)
                throw new PyException("ValueError",(precision?"precision":"width")+" too big at position "+start);
            value=value*10+digit;
        }
        return (int)value;
    }
    String run(){
        StringBuilder out=new StringBuilder();
        while(position<format.length()){
            char literal=format.charAt(position++);
            if(literal!='%'){out.append(literal);continue;}
            int start=position-1;
            if(position<format.length() && format.charAt(position)=='%'){out.append('%');position++;continue;}
            Object value=null; boolean hasKey=false; String argumentKey=null;
            if(position<format.length() && format.charAt(position)=='('){
                if(!mapping)throw new PyException("TypeError","format requires a mapping, not "+typeName(operand));
                int keyStart=++position,depth=1;
                while(position<format.length() && depth>0){char c=format.charAt(position++);if(c=='(')depth++;else if(c==')')depth--;}
                if(depth!=0)throw new PyException("ValueError","stray % or incomplete format key at position "+start);
                argumentKey=format.substring(keyStart,position-1);value=getitem(operand,argumentKey);keyed=true;hasKey=true;
            }else if(keyed)throw new PyException("ValueError","format requires a parenthesised mapping key at position "+start);
            boolean left=false,plus=false,blank=false,alternate=false,zero=false;
            while(position<format.length()){
                char flag=format.charAt(position);
                if(flag=='-')left=true;else if(flag=='+')plus=true;else if(flag==' ')blank=true;
                else if(flag=='#')alternate=true;else if(flag=='0')zero=true;else break;
                position++;
            }
            int width=0,precision=-1;
            if(position<format.length() && format.charAt(position)=='*'){
                position++;width=star(false,start);if(width<0){left=true;if(width==Integer.MIN_VALUE)throw new PyException("OverflowError","format argument "+index+": too big for width");width=-width;}
            }else width=digits(false,start);
            if(position<format.length() && format.charAt(position)=='.'){
                position++;
                if(position<format.length() && format.charAt(position)=='*'){position++;precision=star(true,start);}
                else precision=digits(true,start);
            }
            if(position<format.length() && "hlL".indexOf(format.charAt(position))>=0)position++;
            if(position>=format.length())throw new PyException("ValueError","stray % at position "+format.codePointCount(0,start));
            int conversionPosition=position,code=format.codePointAt(position);
            position+=Character.charCount(code);
            char conversion=code<128?(char)code:0;
            if(!hasKey)value=next(false);
            String body,prefix="";boolean numeric=false;
            try { switch(conversion){
                case 's','r','a' -> {
                    body=conversion=='s'?pyStr(value):conversion=='r'?pyRepr(value):asciiRepr(value);
                    if(precision>=0 && body.codePointCount(0,body.length())>precision)body=body.substring(0,body.offsetByCodePoints(0,precision));
                }
                case 'c' -> {
                    if(value instanceof String text){
                        int length=text.codePointCount(0,text.length());
                        if(length!=1)throw new PyException("TypeError","format argument: %c requires an integer or a unicode character, not a string of length "+length);
                        body=text;
                    }else {
                        BigInteger charCode=percentInteger(value,conversion,false);
                        if(charCode.signum()<0 || charCode.compareTo(BigInteger.valueOf(0x10ffff))>0)throw new PyException("OverflowError","format argument: %c argument not in range(0x110000)");
                        body=new String(Character.toChars(charCode.intValue()));
                    }
                }
                case 'd','i','u','o','x','X' -> {
                    BigInteger integer=percentInteger(value,conversion,"diu".indexOf(conversion)>=0);
                    numeric=true;prefix=integer.signum()<0?"-":plus?"+":blank?" ":"";
                    int radix=conversion=='o'?8:conversion=='x'||conversion=='X'?16:10;
                    body=integer.abs().toString(radix);
                    if(conversion=='X')body=body.toUpperCase(Locale.ROOT);
                    if(precision>body.length())body="0".repeat(precision-body.length())+body;
                    if(alternate && radix!=10)prefix+=radix==8?"0o":conversion=='X'?"0X":"0x";
                }
                case 'e','E','f','F','g','G' -> {
                    double number=percentFloat(value,conversion);numeric=true;
                    boolean negative=!Double.isNaN(number) && Double.doubleToRawLongBits(number)<0;
                    prefix=negative?"-":plus?"+":blank?" ":"";
                    body=floatDigits(Math.abs(number),conversion,precision,alternate);
                }
                default -> {
                    int conversionOffset=format.codePointCount(0,conversionPosition);
                    String display;
                    if(code=='\'')display="\"'\"";
                    else if(code>=32 && code<127)display="'"+(char)code+"'";
                    else if(Character.isISOControl(code))display=String.format(Locale.ROOT,"U+%04X",code);
                    else display="'"+new String(Character.toChars(code))+"' ("+String.format(Locale.ROOT,"U+%04X",code)+")";
                    String message=code<128 && Character.isLetter(code)?"unsupported format %"+(char)code+" at position "+format.codePointCount(0,start):
                        "stray % at position "+format.codePointCount(0,start)+" or unexpected format character "+display+" at position "+conversionOffset;
                    throw new PyException("ValueError",message);
                }
            }
            }catch(PyException error){
                if(error.value instanceof String message && message.startsWith("format argument:")){
                    String context=hasKey?" "+pyRepr(argumentKey):tuple?" "+index:"";
                    throw new PyException(error.typeName,"format argument"+context+message.substring("format argument".length()));
                }
                throw error;
            }
            int padding=Math.max(0,width-prefix.length()-body.codePointCount(0,body.length()));
            if(left)out.append(prefix).append(body).append(" ".repeat(padding));
            else if(zero && numeric)out.append(prefix).append("0".repeat(padding)).append(body);
            else out.append(" ".repeat(padding)).append(prefix).append(body);
        }
        if(!mapping && index<args.size())throw new PyException("TypeError","not all arguments converted during string formatting (required "+index+", got "+args.size()+")");
        return out.toString();
    }
}
