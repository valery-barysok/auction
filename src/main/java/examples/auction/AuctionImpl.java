package examples.auction;

import io.baratine.core.Modify;
import io.baratine.core.OnLoad;
import io.baratine.core.OnSave;
import io.baratine.core.Result;
import io.baratine.core.ServiceManager;
import io.baratine.core.Services;
import io.baratine.db.Cursor;
import io.baratine.db.DatabaseService;
import io.baratine.timer.TimerService;

import java.time.ZonedDateTime;
import java.util.logging.Logger;

public class AuctionImpl implements Auction
{
  private final static Logger log
    = Logger.getLogger(AuctionImpl.class.getName());

  private DatabaseService _db;

  private String _id;
  private AuctionDataPublic _auctionData;

  private AuctionEvents _events;

  public AuctionImpl()
  {
  }

  public AuctionImpl(DatabaseService db, String id)
  {
    _db = db;
    _id = id;
  }

  public void create(String ownerId,
                     String title,
                     int startingBid,
                     Result<String> result)
  {
    ZonedDateTime date = ZonedDateTime.now();
    date = date.plusSeconds(30);
    ZonedDateTime closingDate = date;

    AuctionDataPublic auctionData = new AuctionDataPublic(_id,
                                                          ownerId,
                                                          title,
                                                          startingBid,
                                                          closingDate);

    log.finer("create auction: " + auctionData);

    _db.exec("insert into auction (id, title, value) values (?,?,?)",
             result.chain(o -> {
               _auctionData = auctionData;
               return _id;
             }), _id, title, auctionData);
  }

  @OnSave
  public boolean save()
  {
    log.finer("save auction: " + _auctionData);

    _db.exec("update auction set title=?, value=? where id=?",
             _auctionData.getTitle(),
             _auctionData,
             _auctionData.getId());

    return true;
  }

  @OnLoad
  public void load(Result<Boolean> result)
  {
    _db.findOne("select value from auction where id=?",
                result.chain(c -> loadComplete(c)),
                _id);
  }

  boolean loadComplete(Cursor c)
  {
    if (c != null) {
      _auctionData = (AuctionDataPublic) c.getObject(1);
    }

    log.finer("load auction [" + _id + "]: " + _auctionData);

    return _auctionData != null;
  }

  public void get(Result<AuctionDataPublic> result)
  {
    result.complete(_auctionData);
  }

  @Modify
  public void open(Result<Boolean> result)
  {
    if (_auctionData == null)
      throw new IllegalStateException();

    if (_auctionData.getState() == AuctionDataPublic.State.INIT) {
      _auctionData.toOpen();

      log.finer("open auction: " + _auctionData);

      startTimer();

      result.complete(true);
    }
    else {
      result.complete(false);
    }
  }

  private void startTimer()
  {
    String url = "timer:///";

    ServiceManager manager = Services.getCurrentManager();
    TimerService timer = manager.lookup(url).as(TimerService.class);

    Auction service = manager.currentService()
                             .lookup("/" + _id)
                             .as(Auction.class);

    timer.runAt(() -> service.close(Result.empty()),
                _auctionData.getDateToClose().toInstant().toEpochMilli());

    log.finer("start timer for auction: " + _auctionData);
  }

  /**
   * Administrator can close the auction. Additionally, method is called by
   * a system timer ("timer:) see below
   */
  @Modify
  public void close(Result<Boolean> result)
  {
    if (_auctionData == null)
      throw new IllegalStateException();

    if (_auctionData.getState() == AuctionDataPublic.State.OPEN) {
      _auctionData.toClose();

      log.finer("close auction: " + _auctionData);

      getEvents().onClose(_auctionData);

      result.complete(true);
    }
    else {
      result.complete(false);
    }
  }

  private AuctionEvents getEvents()
  {
    if (_events == null) {
      ServiceManager manager = Services.getCurrentManager();

      String url = "event://auction/auction/" + _auctionData.getId();

      _events = manager.lookup(url).as(AuctionEvents.class);
    }

    return _events;
  }

  @Modify
  public void bid(String userId,
                  int bid,
                  Result<Boolean> result)
    throws IllegalStateException
  {
    if (_auctionData == null)
      throw new IllegalStateException();

    log.finer("bid auction: " + _auctionData);

    boolean isSuccess = _auctionData.bid(userId, bid);

    if (isSuccess) {
      getEvents().onBid(_auctionData);

      result.complete(true);
    }
    else {
      result.complete(false);
    }
  }
}
