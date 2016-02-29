package examples.auction;

import java.util.HashSet;
import java.util.Set;

public class UserData
{
  private long _id;

  private String _name;

  private String _password;
  private boolean _isAdmin;

  private Set<Long> _wonAuctions = new HashSet<>();

  public UserData()
  {
  }

  public UserData(long id,
                  String name,
                  String password,
                  boolean isAdmin)
  {
    _id = id;
    _name = name;
    _password = password;
    _isAdmin = isAdmin;
  }

  public long getId()
  {
    return _id;
  }

  public String getName()
  {
    return _name;
  }

  public String getDigest()
  {
    return _password;
  }

  public boolean isAdmin()
  {
    return _isAdmin;
  }

  public Set<Long> getWonAuctions()
  {
    return _wonAuctions;
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "[" + _id + "," + _name + "]";
  }

  public void addWonAuction(long auctionId)
  {
    _wonAuctions.add(auctionId);
  }

  public void removeWonAuction(long auctionId)
  {
    _wonAuctions.remove(auctionId);
  }
}
