package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.TransactionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LockContext wraps around LockManager to provide the hierarchical structure
 * of multigranularity locking. Calls to acquire/release/etc. locks should
 * be mostly done through a LockContext, which provides access to locking
 * methods at a certain point in the hierarchy (database, table X, etc.)
 */
public class LockContext {
    // You should not remove any of these fields. You may add additional
    // fields/methods as you see fit.

    // The underlying lock manager.
    protected final LockManager lockman;

    // The parent LockContext object, or null if this LockContext is at the top of the hierarchy.
    protected final LockContext parent;

    // The name of the resource this LockContext represents.
    protected ResourceName name;

    // Whether this LockContext is readonly. If a LockContext is readonly, acquire/release/promote/escalate should
    // throw an UnsupportedOperationException.
    protected boolean readonly;

    // A mapping between transaction numbers, and the number of locks on children of this LockContext
    // that the transaction holds.
    protected final Map<Long, Integer> numChildLocks;

    // You should not modify or use this directly.
    protected final Map<String, LockContext> children;

    // Whether or not any new child LockContexts should be marked readonly.
    protected boolean childLocksDisabled;

    public LockContext(LockManager lockman, LockContext parent, String name) {
        this(lockman, parent, name, false);
    }

    protected LockContext(LockManager lockman, LockContext parent, String name,
                          boolean readonly) {
        this.lockman = lockman;
        this.parent = parent;
        if (parent == null) {
            this.name = new ResourceName(name);
        } else {
            this.name = new ResourceName(parent.getResourceName(), name);
        }
        this.readonly = readonly;
        this.numChildLocks = new ConcurrentHashMap<>();
        this.children = new ConcurrentHashMap<>();
        this.childLocksDisabled = readonly;
    }

    /**
     * Gets a lock context corresponding to `name` from a lock manager.
     */
    public static LockContext fromResourceName(LockManager lockman, ResourceName name) {
        Iterator<String> names = name.getNames().iterator();
        LockContext ctx;
        String n1 = names.next();
        ctx = lockman.context(n1);
        while (names.hasNext()) {
            String n = names.next();
            ctx = ctx.childContext(n);
        }
        return ctx;
    }

    /**
     * Get the name of the resource that this lock context pertains to.
     */
    public ResourceName getResourceName() {
        return name;
    }

    /**
     * Acquire a `lockType` lock, for transaction `transaction`.
     *
     * Note: you must make any necessary updates to numChildLocks, or else calls
     * to LockContext#getNumChildren will not work properly.
     *
     * @throws InvalidLockException if the request is invalid
     * @throws DuplicateLockRequestException if a lock is already held by the
     * transaction.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void acquire(TransactionContext transaction, LockType lockType)
            throws InvalidLockException, DuplicateLockRequestException {
        // TODO(proj4_part2): implement
        // Check if the request is readonly.
        if (this.readonly) {
            throw new UnsupportedOperationException("The context is readonly.");
        }
        // Check if a lock is already held by the transaction.
        LockType currLockType = lockman.getLockType(transaction, this.name);
        // Invalid to acquire NL Lock
        if (currLockType == LockType.NL) {
            if (lockType == LockType.NL) {
                throw new InvalidLockException("Can't acquire NL Lock.");
            }
        }
        if (currLockType == lockType) {
            throw new DuplicateLockRequestException("The lock is already held by the transaction.");
        }

        if (parent != null && lockType == LockType.X && parent.getExplicitLockType(transaction) == LockType.IS) {
            throw new InvalidLockException("Cannot acquire an X lock with parent IS Lock.");
        }
        // Check if ancestor has SIX and acquiring an IS/S lock.
        if (hasSIXAncestor(transaction) && (lockType == LockType.IS || lockType == LockType.S)) {
            throw new InvalidLockException("Ancestor has SIX, invalid request.");
        }
        // If we pass, call LockManager to acquire the lock and update the numChildLocks
        lockman.acquire(transaction, name, lockType);

        if (parent != null) {
            parent.updateNumChildLocks(transaction, +1);
        }
    }

    // Helper to update NumChildLocks
    private void updateNumChildLocks(TransactionContext transaction, int num) {
        numChildLocks.put(transaction.getTransNum(), numChildLocks.getOrDefault(transaction.getTransNum(), 0) + num);
    }

    /**
     * Release `transaction`'s lock on `name`.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or
     * else calls to LockContext#getNumChildren will not work properly.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     * @throws InvalidLockException if the lock cannot be released because
     * doing so would violate multigranularity locking constraints
     * @throws UnsupportedOperationException if context is readonly
     */
    public void release(TransactionContext transaction)
            throws NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        if (this.readonly) {
            throw new UnsupportedOperationException("Cannot release, the context is readonly.");
        }
        if (lockman.getLockType(transaction, this.name) == LockType.NL) {
            throw new NoLockHeldException("Cannot release, Not lock on name is held by transaction.");
        }
        // If a child has a lock, the parent's lock should not be released.
        if (getNumChildren(transaction) > 0) {
            throw new InvalidLockException("Cannot release, violating multigranularity locking constraints.");
        }
        // If we pass, release the lock
        lockman.release(transaction, this.name);
        if (parent != null) {
            parent.updateNumChildLocks(transaction, -1);
        }
    }

    /**
     * Promote `transaction`'s lock to `newLockType`. For promotion to SIX from
     * IS/IX, all S and IS locks on descendants must be simultaneously
     * released. The helper function sisDescendants may be helpful here.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or else
     * calls to LockContext#getNumChildren will not work properly.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock
     * @throws NoLockHeldException if `transaction` has no lock
     * @throws InvalidLockException if the requested lock type is not a
     * promotion or promoting would cause the lock manager to enter an invalid
     * state (e.g. IS(parent), X(child)). A promotion from lock type A to lock
     * type B is valid if B is substitutable for A and B is not equal to A, or
     * if B is SIX and A is IS/IX/S, and invalid otherwise. hasSIXAncestor may
     * be helpful here.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void promote(TransactionContext transaction, LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        if (this.readonly) {
            throw new UnsupportedOperationException("The context is readonly");
        }
        // When transaction already has newLockType
        LockType currLockType = lockman.getLockType(transaction, this.name);
        if (currLockType == newLockType) {
            throw new DuplicateLockRequestException("The transaction already has a newLockType.");
        }
        // When the transaction has no lock, NoLockHeldException
        if (currLockType == LockType.NL) {
            throw new NoLockHeldException("Transaction has no lock.");
        }
        // InvalidLockException: newLockType is B
        // A promotion from lock type A to lock type B is valid if B is substitutable
        // for A and B is not equal to A, or if B is SIX and A is IS/IX/S, and invalid otherwise.

        if (!(LockType.substitutable(newLockType, currLockType)) && !(newLockType == LockType.SIX &&
                (currLockType == LockType.IS || currLockType == LockType.IX || currLockType == LockType.S))) {
            throw new InvalidLockException("Requested lock type is not a valid promotion.");
        }
        // Check for SIX ancestor when promoting to SIX
        if (newLockType == LockType.SIX && hasSIXAncestor(transaction)) {
            throw new InvalidLockException("Cannot promote to SIX lock because an ancestor has SIX lock.");
        }
        // Special handling for promoting to SIX
        if (newLockType == LockType.SIX) {
            List<ResourceName> sisDescendantNames = sisDescendants(transaction);
            sisDescendantNames.add(this.name);
            lockman.acquireAndRelease(transaction, this.name, newLockType, sisDescendantNames);
            updateNumChildLocks(transaction, -sisDescendantNames.size());
        } else {
            // For promotions not to SIX, just use the LockManager's promote method
            lockman.promote(transaction, this.name, newLockType);
        }
    }

    /**
     * Escalate `transaction`'s lock from descendants of this context to this
     * level, using either an S or X lock. There should be no descendant locks
     * after this call, and every operation valid on descendants of this context
     * before this call must still be valid. You should only make *one* mutating
     * call to the lock manager, and should only request information about
     * TRANSACTION from the lock manager.
     *
     * For example, if a transaction has the following locks:
     *
     *                    IX(database)
     *                    /         \
     *               IX(table1)    S(table2)
     *                /      \
     *    S(table1 page3)  X(table1 page5)
     *
     * then after table1Context.escalate(transaction) is called, we should have:
     *
     *                    IX(database)
     *                    /         \
     *               X(table1)     S(table2)
     *
     * You should not make any mutating calls if the locks held by the
     * transaction do not change (such as when you call escalate multiple times
     * in a row).
     *
     * Note: you *must* make any necessary updates to numChildLocks of all
     * relevant contexts, or else calls to LockContext#getNumChildren will not
     * work properly.
     *
     * @throws NoLockHeldException if `transaction` has no lock at this level
     * @throws UnsupportedOperationException if context is readonly
     */
    public void escalate(TransactionContext transaction) throws NoLockHeldException {
        // TODO(proj4_part2): implement
        // Check if context is readonly
        if (this.readonly) {
            throw new UnsupportedOperationException("The context is readonly.");
        }
        // Check if the transaction holds a lock at this level
        LockType currLock = lockman.getLockType(transaction, this.name);
        if (currLock == LockType.NL) {
            throw new NoLockHeldException("Transaction has no lock at this level");
        }

        // Identify all descendant locks and decide whether to escalate to an S or X lock
        List<Lock> descendantLocks = lockman.getLocks(transaction);
        LockType newLockType;
        boolean hasXLock = hasXLock(descendantLocks);
        if (hasXLock) { // Check if the descendants have X Lock
            newLockType = LockType.X;
        } else {
            newLockType = LockType.S;
        }
        // Save step if we have required lock type at the current level
        if (currLock == newLockType) {
            return;
        }

        //Acquire and release and update the numChildLocks
        List<ResourceName> resourceNamesToRelease = new ArrayList<>();
        for (Lock lock: descendantLocks) {
            if (lock.name.isDescendantOf(this.name)) {
                resourceNamesToRelease.add(lock.name);
            }
        }

        if (!LockType.substitutable(currLock, newLockType)) {
            if (!currLock.equals(this.name)) {
                resourceNamesToRelease.add(this.name);
            }
            lockman.acquireAndRelease(transaction, this.name, newLockType, resourceNamesToRelease);
        }

        int numChildLocksReleased = 0;
        for (ResourceName nameToRelease: resourceNamesToRelease) {
            if (!nameToRelease.equals(this.name)) {
                numChildLocksReleased++;
            }
        }
        updateNumChildLocks(transaction, -numChildLocksReleased);
    }

    // Helper
    boolean hasXLock(List<Lock> descendantLocks) {
        for (Lock lock: descendantLocks) {
            if (lock.lockType == LockType.X || lock.lockType == LockType.IX) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the type of lock that `transaction` holds at this level, or NL if no
     * lock is held at this level.
     */
    public LockType getExplicitLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        return lockman.getLockType(transaction, this.name);
    }

    /**
     * Gets the type of lock that the transaction has at this level, either
     * implicitly (e.g. explicit S lock at higher level implies S lock at this
     * level) or explicitly. Returns NL if there is no explicit nor implicit
     * lock.
     */
    public LockType getEffectiveLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        LockType explicitLockType = lockman.getLockType(transaction, this.name);
        // If the lock is explicit, return it
        if (explicitLockType != LockType.NL && explicitLockType != LockType.IX && explicitLockType != LockType.IS) {
            return explicitLockType;
        }
        // Traverse ancestors to find the highest-level explicit lock
        LockContext currContext = this;
        while (currContext.parent != null) {  // While is not the highest level
            currContext = currContext.parent;
            LockType parentLockType = lockman.getLockType(transaction, currContext.name);
            if (parentLockType == LockType.S) {
                return LockType.S;
            } else if (parentLockType == LockType.X) {
                return LockType.X;
            } else if (parentLockType == LockType.SIX) {  // SIX only grants S Lock at high level
                if (explicitLockType != LockType.NL) {
                    return explicitLockType;
                } else {
                    return LockType.S;
                }
            }
        }
        return LockType.NL; // NL if intent Locks or no locks
    }

    /**
     * Helper method to see if the transaction holds a SIX lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a SIX at an ancestor, false if not
     */
    private boolean hasSIXAncestor(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        LockContext currContext = this; // Or parent?
        while (currContext != null) {   // Loop until the root of the hierarchy
            LockType lockType = lockman.getLockType(transaction, currContext.getResourceName());
            if (lockType == LockType.SIX) {
                return true;
            }
            currContext = currContext.parentContext();  // Move to the parent context
        }
        return false;
    }

    /**
     * Helper method to get a list of resourceNames of all locks that are S or
     * IS and are descendants of current context for the given transaction.
     * @param transaction the given transaction
     * @return a list of ResourceNames of descendants which the transaction
     * holds an S or IS lock.
     */
    private List<ResourceName> sisDescendants(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        List<ResourceName> descendants = new ArrayList<>(); // Initialize a list for descendants
        // Loop over all the transaction locks
        for (Lock lock: lockman.getLocks(transaction)) {
            // If lock are S or IS
            if (lock.lockType == LockType.S || lock.lockType == LockType.IS) {
                LockContext lockContext = fromResourceName(lockman, lock.name);
                // Check if lock is descendant of current context for the given transaction
                if (isDescendant(lockContext, this) && !lockContext.equals(this)) {
                    descendants.add(lock.name);
                }
            }
        }
        return descendants;
    }

    // Helper to check if the lock is descendant of current context
    private boolean isDescendant(LockContext descendant, LockContext ancestor) {
        LockContext curr = descendant;
        while (curr != null) {
            if (curr == ancestor) {
                return true;
            }
            curr = curr.parentContext();
        }
        return false;
    }

    /**
     * Disables locking descendants. This causes all new child contexts of this
     * context to be readonly. This is used for indices and temporary tables
     * (where we disallow finer-grain locks), the former due to complexity
     * locking B+ trees, and the latter due to the fact that temporary tables
     * are only accessible to one transaction, so finer-grain locks make no
     * sense.
     */
    public void disableChildLocks() {
        this.childLocksDisabled = true;
    }

    /**
     * Gets the parent context.
     */
    public LockContext parentContext() {
        return parent;
    }

    /**
     * Gets the context for the child with name `name` and readable name
     * `readable`
     */
    public synchronized LockContext childContext(String name) {
        LockContext temp = new LockContext(lockman, this, name,
                this.childLocksDisabled || this.readonly);
        LockContext child = this.children.putIfAbsent(name, temp);
        if (child == null) child = temp;
        return child;
    }

    /**
     * Gets the context for the child with name `name`.
     */
    public synchronized LockContext childContext(long name) {
        return childContext(Long.toString(name));
    }

    /**
     * Gets the number of locks held on children a single transaction.
     */
    public int getNumChildren(TransactionContext transaction) {
        return numChildLocks.getOrDefault(transaction.getTransNum(), 0);
    }

    @Override
    public String toString() {
        return "LockContext(" + name.toString() + ")";
    }
}

