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

final class PyComplex {
    final double real, imag;
    PyComplex(double real,double imag){this.real=real;this.imag=imag;}
    @Override public String toString(){
        boolean negativeImag=Double.doubleToRawLongBits(imag)<0 && !Double.isNaN(imag);
        String imaginary=complexComponent(negativeImag?-imag:imag);
        if(real==0.0 && Double.doubleToRawLongBits(real)>=0)
            return (negativeImag?"-":"")+imaginary+"j";
        return "("+complexComponent(real)+(negativeImag?"-":"+")+imaginary+"j)";
    }
}
