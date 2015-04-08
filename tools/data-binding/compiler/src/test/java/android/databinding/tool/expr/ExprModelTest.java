/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool.expr;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import android.databinding.tool.LayoutBinder;
import android.databinding.tool.MockLayoutBinder;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.java.JavaAnalyzer;
import android.databinding.tool.util.L;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ExprModelTest {

    private static class DummyExpr extends Expr {

        String mKey;

        public DummyExpr(String key, DummyExpr... children) {
            super(children);
            mKey = key;
        }

        @Override
        protected ModelClass resolveType(ModelAnalyzer modelAnalyzer) {
            return modelAnalyzer.findClass(Integer.class);
        }

        @Override
        protected List<Dependency> constructDependencies() {
            return constructDynamicChildrenDependencies();
        }

        @Override
        protected String computeUniqueKey() {
            return mKey + super.computeUniqueKey();
        }
    }

    ExprModel mExprModel;

    @Rule
    public TestWatcher mTestWatcher = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            if (mExprModel != null && mExprModel.getFlagMapping() != null) {
                final String[] mapping = mExprModel.getFlagMapping();
                for (int i = 0; i < mapping.length; i++) {
                    L.d("flag %d: %s", i, mapping[i]);
                }
            }
        }
    };

    @Before
    public void setUp() throws Exception {
        JavaAnalyzer.initForTests();
        mExprModel = new ExprModel();
    }

    @Test
    public void testAddNormal() {
        final DummyExpr d = new DummyExpr("a");
        assertSame(d, mExprModel.register(d));
        assertSame(d, mExprModel.register(d));
        assertEquals(1, mExprModel.mExprMap.size());
    }

    @Test
    public void testAddDupe1() {
        final DummyExpr d = new DummyExpr("a");
        assertSame(d, mExprModel.register(d));
        assertSame(d, mExprModel.register(new DummyExpr("a")));
        assertEquals(1, mExprModel.mExprMap.size());
    }

    @Test
    public void testAddMultiple() {
        mExprModel.register(new DummyExpr("a"));
        mExprModel.register(new DummyExpr("b"));
        assertEquals(2, mExprModel.mExprMap.size());
    }


    @Test
    public void testAddWithChildren() {
        DummyExpr a = new DummyExpr("a");
        DummyExpr b = new DummyExpr("b");
        DummyExpr c = new DummyExpr("c", a, b);
        mExprModel.register(c);
        DummyExpr a2 = new DummyExpr("a");
        DummyExpr b2 = new DummyExpr("b");
        DummyExpr c2 = new DummyExpr("c", a, b);
        assertEquals(c, mExprModel.register(c2));
    }

    @Test
    public void testShouldRead() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();
        IdentifierExpr a = lb.addVariable("a", "java.lang.String");
        IdentifierExpr b = lb.addVariable("b", "java.lang.String");
        IdentifierExpr c = lb.addVariable("c", "java.lang.String");
        lb.parse("a == null ? b : c");
        mExprModel.comparison("==", a, mExprModel.symbol("null", Object.class));
        lb.getModel().seal();
        Iterable<Expr> shouldRead = getShouldRead();
        // a and a == null
        assertEquals(2, Iterables.size(shouldRead));
        final Iterable<Expr> readFirst = getReadFirst(shouldRead, null);
        assertEquals(1, Iterables.size(readFirst));
        final Expr first = Iterables.getFirst(readFirst, null);
        assertSame(a, first);
        // now , assume we've read this
        final BitSet shouldReadFlags = first.getShouldReadFlags();
        assertNotNull(shouldReadFlags);
    }

    @Test
    public void testTernaryWithPlus() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();
        IdentifierExpr user = lb
                .addVariable("user", "android.databinding.tool.expr.ExprModelTest.User");
        MathExpr parsed = parse(lb, "user.name + \" \" + (user.lastName ?? \"\")", MathExpr.class);
        mExprModel.seal();
        Iterable<Expr> toRead = getShouldRead();
        Iterable<Expr> readNow = getReadFirst(toRead);
        assertEquals(1, Iterables.size(readNow));
        assertSame(user, Iterables.getFirst(readNow, null));
        List<Expr> justRead = new ArrayList<Expr>();
        justRead.add(user);
        readNow = filterOut(getReadFirst(toRead, justRead), justRead);
        assertEquals(2, Iterables.size(readNow)); //user.name && user.lastName
        Iterables.addAll(justRead, readNow);
        // user.lastname (T, F), user.name + " "
        readNow = filterOut(getReadFirst(toRead, justRead), justRead);
        assertEquals(2, Iterables.size(readNow)); //user.name && user.lastName
        Iterables.addAll(justRead, readNow);
        readNow = filterOut(getReadFirst(toRead, justRead), justRead);
        assertEquals(0, Iterables.size(readNow));
        mExprModel.markBitsRead();

        toRead = getShouldRead();
        assertEquals(2, Iterables.size(toRead));
        justRead.clear();
        readNow = filterOut(getReadFirst(toRead, justRead), justRead);
        assertEquals(1, Iterables.size(readNow));
        assertSame(parsed.getRight(), Iterables.getFirst(readNow, null));
        Iterables.addAll(justRead, readNow);

        readNow = filterOut(getReadFirst(toRead, justRead), justRead);
        assertEquals(1, Iterables.size(readNow));
        assertSame(parsed, Iterables.getFirst(readNow, null));
        Iterables.addAll(justRead, readNow);

        readNow = filterOut(getReadFirst(toRead, justRead), justRead);
        assertEquals(0, Iterables.size(readNow));
        mExprModel.markBitsRead();
        assertEquals(0, Iterables.size(getShouldRead()));
    }

    private List<Expr> filterOut(Iterable itr, final Iterable exclude) {
        return Arrays.asList(Iterables.toArray(Iterables.filter(itr, new Predicate() {
            @Override
            public boolean apply(Object input) {
                return !Iterables.contains(exclude, input);
            }
        }), Expr.class));
    }

    @Test
    public void testTernaryInsideTernary() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();
        IdentifierExpr cond1 = lb.addVariable("cond1", "boolean");
        IdentifierExpr cond2 = lb.addVariable("cond2", "boolean");

        IdentifierExpr a = lb.addVariable("a", "boolean");
        IdentifierExpr b = lb.addVariable("b", "boolean");
        IdentifierExpr c = lb.addVariable("c", "boolean");

        final TernaryExpr ternaryExpr = parse(lb, "cond1 ? cond2 ? a : b : c", TernaryExpr.class);
        final TernaryExpr innerTernary = (TernaryExpr) ternaryExpr.getIfTrue();
        mExprModel.seal();

        Iterable<Expr> toRead = getShouldRead();
        assertEquals(1, Iterables.size(toRead));
        assertEquals(ternaryExpr.getPred(), Iterables.getFirst(toRead, null));

        Iterable<Expr> readNow = getReadFirst(toRead);
        assertEquals(1, Iterables.size(readNow));
        assertEquals(ternaryExpr.getPred(), Iterables.getFirst(readNow, null));
        int cond1True = ternaryExpr.getRequirementFlagIndex(true);
        int cond1False = ternaryExpr.getRequirementFlagIndex(false);
        // ok, it is read now.
        mExprModel.markBitsRead();

        // now it should read cond2 or c, depending on the flag from first
        toRead = getShouldRead();
        assertEquals(2, Iterables.size(toRead));
        assertExactMatch(toRead, ternaryExpr.getIfFalse(), innerTernary.getPred());
        assertFlags(ternaryExpr.getIfFalse(), cond1False);
        assertFlags(ternaryExpr.getIfTrue(), cond1True);

        mExprModel.markBitsRead();

        // now it should read a or b, innerTernary, outerTernary
        toRead = getShouldRead();
        assertExactMatch(toRead, innerTernary.getIfTrue(), innerTernary.getIfFalse(), ternaryExpr,
                innerTernary);
        assertFlags(innerTernary.getIfTrue(), innerTernary.getRequirementFlagIndex(true));
        assertFlags(innerTernary.getIfFalse(), innerTernary.getRequirementFlagIndex(false));
        assertFalse(mExprModel.markBitsRead());
    }

    @Test
    public void testRequirementFlags() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();
        IdentifierExpr a = lb.addVariable("a", "java.lang.String");
        IdentifierExpr b = lb.addVariable("b", "java.lang.String");
        IdentifierExpr c = lb.addVariable("c", "java.lang.String");
        IdentifierExpr d = lb.addVariable("d", "java.lang.String");
        IdentifierExpr e = lb.addVariable("e", "java.lang.String");
        final Expr aTernary = lb.parse("a == null ? b == null ? c : d : e");
        assertTrue(aTernary instanceof TernaryExpr);
        final Expr bTernary = ((TernaryExpr) aTernary).getIfTrue();
        assertTrue(bTernary instanceof TernaryExpr);
        final Expr aIsNull = mExprModel
                .comparison("==", a, mExprModel.symbol("null", Object.class));
        final Expr bIsNull = mExprModel
                .comparison("==", b, mExprModel.symbol("null", Object.class));
        lb.getModel().seal();
        Iterable<Expr> shouldRead = getShouldRead();
        // a and a == null
        assertEquals(2, Iterables.size(shouldRead));
        assertFalse(a.getShouldReadFlags().isEmpty());
        assertTrue(a.getShouldReadFlags().get(a.getId()));
        assertTrue(b.getShouldReadFlags().isEmpty());
        assertTrue(c.getShouldReadFlags().isEmpty());
        assertTrue(d.getShouldReadFlags().isEmpty());
        assertTrue(e.getShouldReadFlags().isEmpty());

        Iterable<Expr> readFirst = getReadFirst(shouldRead, null);
        assertEquals(1, Iterables.size(readFirst));
        final Expr first = Iterables.getFirst(readFirst, null);
        assertSame(a, first);
        assertTrue(mExprModel.markBitsRead());
        for (Expr expr : mExprModel.getPendingExpressions()) {
            assertNull(expr.mShouldReadFlags);
        }
        shouldRead = getShouldRead();
        assertExactMatch(shouldRead, e, b, bIsNull);

        assertFlags(e, aTernary.getRequirementFlagIndex(false));

        assertFlags(b, aTernary.getRequirementFlagIndex(true));
        assertFlags(bIsNull, aTernary.getRequirementFlagIndex(true));
        assertTrue(mExprModel.markBitsRead());
        shouldRead = getShouldRead();
        assertEquals(4, Iterables.size(shouldRead));
        assertTrue(Iterables.contains(shouldRead, c));
        assertTrue(Iterables.contains(shouldRead, d));
        assertTrue(Iterables.contains(shouldRead, aTernary));
        assertTrue(Iterables.contains(shouldRead, bTernary));

        assertTrue(c.getShouldReadFlags().get(bTernary.getRequirementFlagIndex(true)));
        assertEquals(1, c.getShouldReadFlags().cardinality());

        assertTrue(d.getShouldReadFlags().get(bTernary.getRequirementFlagIndex(false)));
        assertEquals(1, d.getShouldReadFlags().cardinality());

        assertTrue(bTernary.getShouldReadFlags().get(aTernary.getRequirementFlagIndex(true)));
        assertEquals(1, bTernary.getShouldReadFlags().cardinality());

        assertEquals(5, aTernary.getShouldReadFlags().cardinality());
        for (Expr expr : new Expr[]{a, b, c, d, e}) {
            assertTrue(aTernary.getShouldReadFlags().get(expr.getId()));
        }

        readFirst = getReadFirst(shouldRead);
        assertEquals(2, Iterables.size(readFirst));
        assertTrue(Iterables.contains(readFirst, c));
        assertTrue(Iterables.contains(readFirst, d));
        assertFalse(mExprModel.markBitsRead());
    }

    @Test
    public void testPostConditionalDependencies() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();

        IdentifierExpr u1 = lb.addVariable("u1", User.class.getCanonicalName());
        IdentifierExpr u2 = lb.addVariable("u2", User.class.getCanonicalName());
        IdentifierExpr a = lb.addVariable("a", int.class.getCanonicalName());
        IdentifierExpr b = lb.addVariable("b", int.class.getCanonicalName());
        IdentifierExpr c = lb.addVariable("c", int.class.getCanonicalName());
        IdentifierExpr d = lb.addVariable("d", int.class.getCanonicalName());
        IdentifierExpr e = lb.addVariable("e", int.class.getCanonicalName());
        TernaryExpr abTernary = parse(lb, "a > b ? u1.name : u2.name", TernaryExpr.class);
        TernaryExpr bcTernary = parse(lb, "b > c ? u1.getCond(d) ? u1.lastName : u2.lastName : `xx`"
                + " + u2.getCond(e) ", TernaryExpr.class);
        Expr abCmp = abTernary.getPred();
        Expr bcCmp = bcTernary.getPred();
        Expr u1GetCondD = ((TernaryExpr) bcTernary.getIfTrue()).getPred();
        final MathExpr xxPlusU2getCondE = (MathExpr) bcTernary.getIfFalse();
        Expr u2GetCondE = xxPlusU2getCondE.getRight();
        Expr u1Name = abTernary.getIfTrue();
        Expr u2Name = abTernary.getIfFalse();
        Expr u1LastName = ((TernaryExpr) bcTernary.getIfTrue()).getIfTrue();
        Expr u2LastName = ((TernaryExpr) bcTernary.getIfTrue()).getIfFalse();

        mExprModel.seal();
        Iterable<Expr> shouldRead = getShouldRead();

        assertExactMatch(shouldRead, a, b, c, abCmp, bcCmp);

        Iterable<Expr> firstRead = getReadFirst(shouldRead);

        assertExactMatch(firstRead, a, b, c);

        assertFlags(a, a, b, u1, u2, u1Name, u2Name);
        assertFlags(b, a, b, u1, u2, u1Name, u2Name, c, d, u1LastName, u2LastName, e);
        assertFlags(c, b, c, u1, d, u1LastName, u2LastName, e);
        assertFlags(abCmp, a, b, u1, u2, u1Name, u2Name);
        assertFlags(bcCmp, b, c, u1, d, u1LastName, u2LastName, e);

        assertTrue(mExprModel.markBitsRead());

        shouldRead = getShouldRead();
        Expr[] batch = {d, e, u1, u2, u1GetCondD, u2GetCondE, xxPlusU2getCondE, abTernary,
                abTernary.getIfTrue(), abTernary.getIfFalse()};
        assertExactMatch(shouldRead, batch);
        firstRead = getReadFirst(shouldRead);
        assertExactMatch(firstRead, d, e, u1, u2);

        assertFlags(d, bcTernary.getRequirementFlagIndex(true));
        assertFlags(e, bcTernary.getRequirementFlagIndex(false));
        assertFlags(u1, bcTernary.getRequirementFlagIndex(true),
                abTernary.getRequirementFlagIndex(true));
        assertFlags(u2, bcTernary.getRequirementFlagIndex(false),
                abTernary.getRequirementFlagIndex(false));

        assertFlags(u1GetCondD, bcTernary.getRequirementFlagIndex(true));
        assertFlags(u2GetCondE, bcTernary.getRequirementFlagIndex(false));
        assertFlags(xxPlusU2getCondE, bcTernary.getRequirementFlagIndex(false));
        assertFlags(abTernary, a, b, u1, u2, u1Name, u2Name);
        assertFlags(abTernary.getIfTrue(), abTernary.getRequirementFlagIndex(true));
        assertFlags(abTernary.getIfFalse(), abTernary.getRequirementFlagIndex(false));

        assertTrue(mExprModel.markBitsRead());

        shouldRead = getShouldRead();
        // actually, there is no real case to read u1 anymore because if b>c was not true,
        // u1.getCond(d) will never be set. Right now, we don't have mechanism to figure this out
        // and also it does not affect correctness (just an unnecessary if stmt)
        assertExactMatch(shouldRead, u2, u1LastName, u2LastName, bcTernary.getIfTrue(), bcTernary);
        firstRead = getReadFirst(shouldRead);
        assertExactMatch(firstRead, u1LastName, u2);

        assertFlags(u1LastName, bcTernary.getIfTrue().getRequirementFlagIndex(true));
        assertFlags(u2LastName, bcTernary.getIfTrue().getRequirementFlagIndex(false));
        assertFlags(u2, bcTernary.getIfTrue().getRequirementFlagIndex(false));

        assertFlags(bcTernary.getIfTrue(), bcTernary.getRequirementFlagIndex(true));
        assertFlags(bcTernary, b, c, u1, u2, d, u1LastName, u2LastName, e);
    }

    @Test
    public void testCircularDependency() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();
        IdentifierExpr a = lb.addVariable("a", int.class.getCanonicalName());
        IdentifierExpr b = lb.addVariable("b", int.class.getCanonicalName());
        final TernaryExpr abTernary = parse(lb, "a > 3 ? a : b", TernaryExpr.class);
        mExprModel.seal();
        Iterable<Expr> shouldRead = getShouldRead();
        assertExactMatch(shouldRead, a, abTernary.getPred());
        assertTrue(mExprModel.markBitsRead());
        shouldRead = getShouldRead();
        assertExactMatch(shouldRead, b, abTernary);
        assertFalse(mExprModel.markBitsRead());
    }

    @Test
    public void testNestedCircularDependency() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();
        IdentifierExpr a = lb.addVariable("a", int.class.getCanonicalName());
        IdentifierExpr b = lb.addVariable("b", int.class.getCanonicalName());
        IdentifierExpr c = lb.addVariable("c", int.class.getCanonicalName());
        final TernaryExpr a3Ternary = parse(lb, "a > 3 ? c > 4 ? a : b : c", TernaryExpr.class);
        final TernaryExpr c4Ternary = (TernaryExpr) a3Ternary.getIfTrue();
        mExprModel.seal();
        Iterable<Expr> shouldRead = getShouldRead();
        assertExactMatch(shouldRead, a, a3Ternary.getPred());
        assertTrue(mExprModel.markBitsRead());
        shouldRead = getShouldRead();
        assertExactMatch(shouldRead, c, c4Ternary.getPred());
        assertFlags(c, a3Ternary.getRequirementFlagIndex(true),
                a3Ternary.getRequirementFlagIndex(false));
        assertFlags(c4Ternary.getPred(), a3Ternary.getRequirementFlagIndex(true));
    }

    @Test
    public void testNoFlagsForNonBindingStatic() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();
        lb.addVariable("a", int.class.getCanonicalName());
        final MathExpr parsed = parse(lb, "a * (3 + 2)", MathExpr.class);
        mExprModel.seal();
        assertTrue(parsed.getRight().getInvalidFlags().isEmpty());
        assertEquals(1, parsed.getLeft().getInvalidFlags().cardinality());
        assertEquals(1, mExprModel.getInvalidateableFieldLimit());
    }

    @Test
    public void testFlagsForBindingStatic() {
        LayoutBinder lb = new MockLayoutBinder();
        mExprModel = lb.getModel();
        lb.addVariable("a", int.class.getCanonicalName());
        final Expr staticParsed = parse(lb, "3 + 2", MathExpr.class);
        final MathExpr parsed = parse(lb, "a * (3 + 2)", MathExpr.class);
        mExprModel.seal();
        assertTrue(staticParsed.isBindingExpression());
        assertEquals(1, staticParsed.getInvalidFlags().cardinality());
        assertEquals(parsed.getRight().getInvalidFlags(), staticParsed.getInvalidFlags());
        assertEquals(1, parsed.getLeft().getInvalidFlags().cardinality());
        assertEquals(2, mExprModel.getInvalidateableFieldLimit());
    }

    @Test
    public void testPartialNeededRead() {
        throw new NotImplementedException("create a test that has a variable which can be read for "
                + "some flags and also may be read for some condition. Try both must match and"
                + " partial match and none-match in conditionals");
    }

    private void assertFlags(Expr a, int... flags) {
        BitSet bitset = new BitSet();
        for (int flag : flags) {
            bitset.set(flag);
        }
        assertEquals("flag test for " + a.getUniqueKey(), bitset, a.getShouldReadFlags());
    }

    private void assertFlags(Expr a, Expr... exprs) {
        BitSet bitSet = a.getShouldReadFlags();
        for (Expr expr : exprs) {
            BitSet clone = (BitSet) bitSet.clone();
            clone.and(expr.getInvalidFlags());
            assertEquals("should read flags of " + a.getUniqueKey() + " should include " + expr
                    .getUniqueKey(), expr.getInvalidFlags(), clone);
        }

        BitSet composite = new BitSet();
        for (Expr expr : exprs) {
            composite.or(expr.getInvalidFlags());
        }
        assertEquals("composite flags should match", composite, bitSet);
    }

    private void assertExactMatch(Iterable<Expr> iterable, Expr... exprs) {
        int i = 0;
        log("list", iterable);
        for (Expr expr : exprs) {
            assertTrue((i++) + ":must contain " + expr.getUniqueKey(),
                    Iterables.contains(iterable, expr));
        }
        i = 0;
        for (Expr expr : iterable) {
            assertTrue((i++) + ":must be expected " + expr.getUniqueKey(),
                    ArrayUtils.contains(exprs, expr));
        }
    }

    private <T extends Expr> T parse(LayoutBinder binder, String input, Class<T> klass) {
        final Expr parsed = binder.parse(input);
        assertTrue(klass.isAssignableFrom(parsed.getClass()));
        return (T) parsed;
    }

    private void log(String s, Iterable<Expr> iterable) {
        L.d(s);
        for (Expr e : iterable) {
            L.d(": %s : %s allFlags: %s readSoFar: %s", e.getUniqueKey(), e.getShouldReadFlags(),
                    e.getShouldReadFlagsWithConditionals(), e.getReadSoFar());
        }
        L.d("end of %s", s);
    }

    private Iterable<Expr> getReadFirst(Iterable<Expr> shouldRead) {
        return getReadFirst(shouldRead, null);
    }

    private Iterable<Expr> getReadFirst(Iterable<Expr> shouldRead, final Iterable<Expr> justRead) {
        return Iterables.filter(shouldRead, new Predicate<Expr>() {
            @Override
            public boolean apply(Expr input) {
                return input.shouldReadNow(justRead);
            }
        });
    }

    private Iterable<Expr> getShouldRead() {
        return mExprModel.filterShouldRead(mExprModel.getPendingExpressions());
    }

    public static class User {

        String name;

        String lastName;

        public String getName() {
            return name;
        }

        public String getLastName() {
            return lastName;
        }

        public boolean getCond(int i) {
            return true;
        }
    }
}
