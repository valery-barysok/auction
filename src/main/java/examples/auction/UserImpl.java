package examples.auction;

import io.baratine.core.Journal;
import io.baratine.core.Modify;
import io.baratine.core.OnLoad;
import io.baratine.core.OnSave;
import io.baratine.core.Result;
import io.baratine.db.Cursor;
import io.baratine.db.DatabaseService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.logging.Logger;

@Journal(count = 1)
public class UserImpl implements User
{
  private static final Logger log = Logger.getLogger(UserImpl.class.getName());

  private DatabaseService _db;
  private MessageDigest _digest;

  private String _id;
  private UserDataPublic _user;

  public UserImpl()
  {
  }

  public UserImpl(DatabaseService db, String id)
  {
    _db = db;
    _id = id;
  }

  @Override
  @Modify
  public void create(String userName, String password, Result<String> userId)
  {
    _user = new UserDataPublic(_id, userName, digest(password));

    userId.complete(_id);

    log.finer("creating new user: " + userName);
  }

  @OnLoad
  public void load(Result<Boolean> result)
  {
    _db.findOne("select value from users where id=?",
                result.from(c -> setUser(c)), _id);
  }

  private boolean setUser(Cursor c)
  {
    if (c != null)
      _user = (UserDataPublic) c.getObject(1);

    log.finer("loading user: " + _id + " ->" + _user);

    return _user != null;
  }

  @OnSave
  public void save()
  {
    log.finer("saving user: " + _user);

    _db.exec("insert into users(id, name, value) values(?,?,?)",
             Result.empty(),
             _id,
             _user.getName(),
             _user);
  }

  @Override
  public void authenticate(String password, Result<Boolean> result)
  {
    if (_user == null)
      throw new IllegalStateException();

    result.complete(_user.getDigest().equals(digest(password)));
  }

  @Override
  public void get(Result<UserDataPublic> user)
  {
    user.complete(_user);
  }

  public String digest(String password)
  {
    try {
      if (_digest == null)
        _digest = MessageDigest.getInstance("SHA-1");

      _digest.reset();

      //for production add salt
      _digest.update(password.getBytes(StandardCharsets.UTF_8));

      String digest = Base64.getEncoder().encodeToString(_digest.digest());

      return digest;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }
}


