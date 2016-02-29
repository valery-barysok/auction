package examples.auction;

import java.util.List;

import io.baratine.service.Result;
import io.baratine.service.Service;

/**
 * User visible channel facade at session://web/auction-session
 */
public interface AuctionSession
{
  void createUser(String userName, String password, Result<Boolean> result);

  void validateLogin(String userName, String password, Result<Boolean> result);

  void getUser(Result<UserData> result);

  void createAuction(String title, int bid, Result<Long> result);

  void getAuction(String id, Result<AuctionDataPublic> result);

  void findAuction(String title, Result<Auction> result);

  void search(String query, Result<List<Long>> result);

  void bidAuction(String id, int bid, Result<Boolean> result);

  void setListener(@Service ChannelListener listener,
                   Result<Boolean> result);

  void addAuctionListener(String idAuction,
                          Result<Boolean> result);

  void logout(Result<Boolean> result);
}
