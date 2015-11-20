package examples.auction.s1;

import examples.auction.Auction;
import examples.auction.AuctionDataPublic;
import examples.auction.CreditCard;
import examples.auction.PayPal;
import examples.auction.Payment;
import examples.auction.User;
import io.baratine.core.Journal;
import io.baratine.core.Modify;
import io.baratine.core.OnInit;
import io.baratine.core.OnLoad;
import io.baratine.core.OnSave;
import io.baratine.core.Result;
import io.baratine.core.ResultFuture;
import io.baratine.core.ServiceManager;
import io.baratine.core.ServiceRef;
import io.baratine.db.Cursor;
import io.baratine.db.DatabaseService;

import static examples.auction.Payment.PayPalResult;
import static examples.auction.s1.TransactionState.CommitState;
import static examples.auction.s1.TransactionState.RollbackState;

@Journal()
public class AuctionSettlementImpl
  implements AuctionSettlement, AuctionSettlementInternal
{
  private DatabaseService _db;
  private PayPal _payPal;
  private ServiceRef _userManager;
  private ServiceRef _auctionManager;

  private BoundState _boundState = BoundState.UNBOUND;
  private String _id;
  private String _auctionId;
  private String _userId;
  private AuctionDataPublic.Bid _bid;
  private TransactionState _state;

  private AuctionSettlementInternal _self;

  public AuctionSettlementImpl(String id)
  {
    _id = id;
  }

  //id, auction_id, user_id, bid
  @OnLoad
  public void load(Result<Boolean> result)
  {
    if (_boundState != BoundState.UNBOUND)
      throw new IllegalStateException();

    Result<Boolean>[] results = result.fork(2, (x -> x.get(0)));

    _db.findLocal(
      "select auction_id, user_id, bid from settlement where id = ?",
      _id).first().result(results[0].from(c -> loadSettlement(c)));

    _db.findLocal("select state from settlement_state where id = ?",
                  _id).first().result(results[1].from(c -> loadState(c)));
  }

  public boolean loadSettlement(Cursor settlement)
  {
    if (settlement != null) {
      _auctionId = settlement.getString(1);

      _userId = settlement.getString(2);

      _bid = (AuctionDataPublic.Bid) settlement.getObject(3);

      _boundState = BoundState.BOUND;
    }

    return true;
  }

  public boolean loadState(Cursor state)
  {
    if (state != null)
      _state = (TransactionState) state.getObject(1);

    return true;
  }

  @OnInit
  public boolean init()
  {
    ServiceManager manager = ServiceManager.current();

    _payPal = manager.lookup("pod://auction/paypal").as(PayPal.class);
    _userManager = manager.lookup("pod://user/user");
    _auctionManager = manager.lookup("pod://auction/auction");

    return true;
  }

  @Modify
  @Override
  public void create(String auctionId,
                     String userId,
                     AuctionDataPublic.Bid bid,
                     Result<Boolean> result)
  {
    if (_boundState != BoundState.UNBOUND)
      throw new IllegalStateException();

    _auctionId = auctionId;
    _userId = userId;
    _bid = bid;

    _boundState = BoundState.NEW;
  }

  @Override
  @Modify
  public void commit(Result<Status> status)
  {
    if (_boundState == BoundState.UNBOUND)
      throw new IllegalStateException();

    if (_state == null)
      _state = new TransactionState();

    _self.commitImpl(status);
  }

  @Override
  @Modify
  public void rollback(Result<Status> status)
  {
    if (_boundState == BoundState.UNBOUND)
      throw new IllegalStateException();

    if (_state == null)
      throw new IllegalStateException();

    if (_state.getRollbackState() == null)
      _state.setRollbackState(RollbackState.PENDING);

    _self.rollbackImpl(status);
  }

  @Override
  @Modify
  public void commitImpl(Result<Status> status)
  {
    CommitState commitState = _state.getCommitState();
    RollbackState rollbackState = _state.getRollbackState();

    if (rollbackState != null)
      throw new IllegalStateException();

    switch (commitState) {
    case COMPLETED: {
      status.complete(Status.COMMITTED);
      break;
    }
    case PENDING: {
      commitPending(status.from(x -> processCommit(x)));
      break;
    }
    case REJECTED_PAYMENT: {
      status.complete(Status.ROLLING_BACK);
      break;
    }
    case REJECTED_USER: {
      status.complete(Status.ROLLING_BACK);
      break;
    }
    case REJECTED_AUCTION: {
      status.complete(Status.ROLLING_BACK);
      break;
    }
    default: {
      break;
    }
    }
  }

  public void commitPending(Result<TransactionState.CommitState> status)
  {
    Result<TransactionState.CommitState>[] children
      = status.fork(3, (s, r) -> r.complete(
      TransactionState.CommitState.COMPLETED),
                    (s, e, r) -> {});

    updateUser(children[0]);
    updateAuction(children[1]);
    chargeUser(children[2]);
  }

  public void updateUser(Result<TransactionState.CommitState> status)
  {
    User user = getUser();

    user.addWonAuction(_auctionId,
                       status.from(x -> x ?
                         CommitState.COMPLETED :
                         CommitState.REJECTED_USER));
  }

  public void updateAuction(Result<CommitState> status)
  {
    Auction auction = getAuction();

    auction.setAuctionWinner(_userId, status.from(x -> x ?
      CommitState.COMPLETED :
      CommitState.REJECTED_AUCTION));
  }

  public void chargeUser(Result<CommitState> status)
  {
    User user = getUser();
    Auction auction = getAuction();

    ResultFuture<Boolean> fork = new ResultFuture<>();
    Result<Boolean>[] forked
      = fork.fork(2, (x, r) -> r.complete(x.get(0) && x.get(1)));

    final ValueRef<AuctionDataPublic> auctionData = new ValueRef();

    auction.get(forked[0].from(d -> {
      auctionData.set(d);
      return d != null;
    }));

    final ValueRef<CreditCard> creditCard = new ValueRef();

    user.getCreditCard(forked[1].from(c -> {
      creditCard.set(c);
      return c != null;
    }));

    boolean forkResult = fork.get();

    //TODO: check fork result

    _payPal.settle(auctionData.get(),
                   _bid,
                   creditCard.get(),
                   _userId,
                   _id,
                   status.from(x -> processPayment(x)));
  }

  private CommitState processPayment(Payment payment)
  {
    _state.setPayment(payment);

    if (payment.getStatus().equals(PayPalResult.approved)) {
      return CommitState.COMPLETED;
    }
    else {
      return CommitState.REJECTED_PAYMENT;
    }
  }

  private Status processCommit(CommitState commitState)
  {
    _state.setCommitState(commitState);

    switch (commitState) {
    case COMPLETED: {
      return Status.COMMITTED;
    }
    case PENDING: {
      return Status.PENDING;
    }
    case REJECTED_AUCTION:
    case REJECTED_PAYMENT:
    case REJECTED_USER: {
      return Status.ROLLING_BACK;
    }
    default: {
      throw new IllegalStateException();
    }
    }
  }

  @Override
  public void rollbackImpl(Result<Status> status)
  {
    RollbackState rollbackState = _state.getRollbackState();

    switch (rollbackState) {
    case COMPLETED: {
      status.complete(Status.ROLLED_BACK);
      break;
    }
    case PENDING: {
      rollbackPending(status.from(x -> processRollback(x)));
      break;
    }
    case FAILED: {
      //TODO:
      break;
    }
    default: {
      throw new IllegalStateException();
    }
    }
  }

  private Status processRollback(RollbackState state)
  {
    switch (state) {
    case COMPLETED:
      return Status.ROLLED_BACK;
    case PENDING:
      return Status.ROLLING_BACK;
    default: {
      throw new IllegalStateException();
    }
    }
  }

  public void rollbackPending(Result<TransactionState.RollbackState> status)
  {
    Result<TransactionState.RollbackState>[] children
      = status.fork(3, (s, r) -> r.complete(
      TransactionState.RollbackState.COMPLETED),
                    (s, e, r) -> {});

    resetUser(children[0]);
    resetAuction(children[1]);
    refundUser(children[2]);
  }

  private void resetUser(Result<RollbackState> child)
  {
    User user = getUser();
    user.removeWonAuction(_auctionId, child.from(x -> RollbackState.COMPLETED));
  }

  private void resetAuction(Result<RollbackState> child)
  {
    Auction auction = getAuction();

    auction.resetAuctionWinner(_userId,
                               child.from(x -> RollbackState.COMPLETED));
  }

  private void refundUser(Result<RollbackState> child)
  {
    _payPal.refund();
  }

  private User getUser()
  {
    User user = _userManager.lookup('/' + _userId).as(User.class);

    return user;
  }

  private Auction getAuction()
  {
    Auction auction = _userManager.lookup('/' + _auctionId).as(Auction.class);

    return auction;
  }

  @OnSave
  public void save()
  {
    //id , auction_id , user_id , bid
    if (_boundState == BoundState.NEW) {
      _db.exec(
        "insert into settlement (id, auction_id, user_id, bid) values (?, ?, ?, ?)",
        x -> _boundState = BoundState.BOUND,
        _id,
        _auctionId,
        _userId,
        _bid);
    }

    _db.exec("insert into settlement_state (id, state) values (?, ?)",
             x -> {
             },
             _id,
             _state);
  }

  enum BoundState
  {
    UNBOUND,
    NEW,
    BOUND
  }
}

interface AuctionSettlementInternal extends AuctionSettlement
{
  void commitImpl(Result<Status> status);

  void rollbackImpl(Result<Status> status);
}

class ValueRef<T>
{
  private T _t;

  T get()
  {
    return _t;
  }

  T set(T t)
  {
    _t = t;

    return t;
  }
}
