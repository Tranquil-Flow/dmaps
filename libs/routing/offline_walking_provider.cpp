#include "routing/offline_walking_provider.hpp"

#include "base/thread_log_guard.hpp"
#include "coding/file_reader.hpp"
#include "indexer/data_source.hpp"
#include "routing/checkpoints.hpp"
#include "routing/index_router.hpp"
#include "routing/route.hpp"
#include "routing/routing_settings.hpp"
#include "traffic/traffic_cache.hpp"
#include "geometry/mercator.hpp"
#include "monocypher.h"
#include <array>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <random>
#include <stdexcept>
#include <limits>
#include <cerrno>
#if !defined(_WIN32)
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace routing
{
namespace
{
namespace fs=std::filesystem;
constexpr uint64_t kMaxMapBytes=uint64_t{2}*1024*1024*1024;
constexpr int64_t kMaxDeparture=10000000000LL;
constexpr size_t kMaxPoints=32768;
struct Digest
{
  crypto_blake2b_ctx ctx;
  Digest(){crypto_blake2b_init(&ctx,32);}
  void Bytes(void const * bytes,size_t n){crypto_blake2b_update(&ctx,static_cast<uint8_t const *>(bytes),n);}
  void U64(uint64_t n){uint8_t b[8];for(int i=7;i>=0;--i){b[i]=static_cast<uint8_t>(n);n>>=8;}Bytes(b,8);}
  void Text(std::string const & s){U64(s.size());Bytes(s.data(),s.size());}
  void Number(double value){if(value==0)value=0;uint64_t n;static_assert(sizeof(n)==sizeof(value));std::memcpy(&n,&value,sizeof(n));U64(n);}
  std::string Finish(){std::array<uint8_t,32> raw{};crypto_blake2b_final(&ctx,raw.data());char const hex[]="0123456789abcdef";std::string out;out.reserve(64);for(auto n:raw){out.push_back(hex[n>>4]);out.push_back(hex[n&15]);}return out;}
};
struct OwnedDirectory
{
  fs::path path;
  explicit OwnedDirectory(std::string const & cache){
    auto root=fs::canonical(cache);if(!fs::is_directory(root))throw std::runtime_error("walking cache unavailable");
    std::random_device random;
    for(int attempt=0;attempt<32;++attempt){
      auto candidate=root/("dmaps-walk-"+std::to_string(random())+"-"+std::to_string(random()));
      if(!fs::create_directory(candidate))continue;
      path=candidate;
      try{fs::permissions(path,fs::perms::owner_all,fs::perm_options::replace);}catch(...){std::error_code ignored;fs::remove(path,ignored);path.clear();throw;}
      return;
    }
    throw std::runtime_error("walking cache unavailable");
  }
  ~OwnedDirectory(){if(!path.empty()){std::error_code ignored;fs::remove_all(path,ignored);}}
};
// A copied map still needs source-incarnation identity. Retain the original descriptor so
// an unlinked inode cannot be recycled into a false "current" result while the copy lives.
struct PinnedCopySource
{
#if !defined(_WIN32)
  struct Descriptor
  {
    int value=-1;
    ~Descriptor(){if(value>=0)::close(value);}
    Descriptor()=default;
    Descriptor(Descriptor const &)=delete;
    Descriptor & operator=(Descriptor const &)=delete;
  } descriptor;
  struct stat stamp{};
  uint64_t bytes=0;
  static bool Same(struct stat const & a,struct stat const & b){
#if defined(__APPLE__)
    bool sameTime=a.st_mtimespec.tv_sec==b.st_mtimespec.tv_sec&&a.st_mtimespec.tv_nsec==b.st_mtimespec.tv_nsec;
#else
    bool sameTime=a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec;
#endif
    return S_ISREG(a.st_mode)&&S_ISREG(b.st_mode)&&a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&sameTime;
  }
  PinnedCopySource(fs::path const & source,uint64_t budget){
    descriptor.value=::open(source.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(descriptor.value<0||::fstat(descriptor.value,&stamp)!=0||!S_ISREG(stamp.st_mode)||stamp.st_ino==0||stamp.st_size<=0)
      throw std::runtime_error("walking source unavailable");
    bytes=static_cast<uint64_t>(stamp.st_size);
    if(budget==0||bytes>budget||bytes>kMaxMapBytes)throw std::runtime_error("walking copy budget exceeded");
  }
  bool Current(fs::path const & source)const{
    struct stat pinned{},named{};
    return ::fstat(descriptor.value,&pinned)==0&&::lstat(source.c_str(),&named)==0&&Same(stamp,pinned)&&Same(stamp,named);
  }
  void CopyTo(fs::path const & destination)const{
    if(fs::space(destination.parent_path()).available<bytes)throw std::runtime_error("walking copy space unavailable");
    Descriptor output;output.value=::open(destination.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);
    if(output.value<0)throw std::runtime_error("walking copy unavailable");
    std::array<uint8_t,65536> block{};
    for(uint64_t at=0;at<bytes;){
      size_t count=static_cast<size_t>(std::min<uint64_t>(block.size(),bytes-at));ssize_t read;
      do{read=::pread(descriptor.value,block.data(),count,static_cast<off_t>(at));}while(read<0&&errno==EINTR);
      if(read<=0)throw std::runtime_error("walking copy read failed");
      for(ssize_t written=0;written<read;){ssize_t n;do{n=::write(output.value,block.data()+written,static_cast<size_t>(read-written));}while(n<0&&errno==EINTR);if(n<=0)throw std::runtime_error("walking copy write failed");written+=n;}
      at+=static_cast<uint64_t>(read);
    }
    // Kernel writes are sufficient for this ephemeral snapshot; no power-loss durability claim.
    int fd=output.value;output.value=-1;if(::close(fd)!=0)throw std::runtime_error("walking copy close failed");
  }
#else
  PinnedCopySource(fs::path const &,uint64_t){throw std::runtime_error("walking copy unsupported on this platform");}
  bool Current(fs::path const &)const{return false;}
  void CopyTo(fs::path const &)const{throw std::runtime_error("walking copy unsupported on this platform");}
#endif
};
bool Valid(m2::PointD const & p){return std::isfinite(p.x)&&std::isfinite(p.y)&&mercator::ValidX(p.x)&&mercator::ValidY(p.y);}
OfflineWalkingProvider::Result Failure(OfflineWalkingProvider::Status status){OfflineWalkingProvider::Result result;result.status=status;return result;}
OfflineWalkingProvider::Status CancelStatus(RouterDelegate const & delegate){
  return delegate.GetCancellable().CancellationStatus()==base::Cancellable::Status::DeadlineExceeded
      ?OfflineWalkingProvider::Status::LimitReached:OfflineWalkingProvider::Status::Cancelled;
}
}

struct OfflineWalkingProvider::Impl
{
  // Destruction is reverse order: router/held readers before the cache alias is removed.
  OwnedDirectory directory;
  fs::path original,snapshot;
  std::unique_ptr<PinnedCopySource> copiedSource;
  fs::file_time_type modified;
  uint64_t bytes=0;
  std::string identity,name;
  FrozenDataSource source;
  traffic::TrafficCache traffic;
  MwmSet::MwmHandle held;
  std::shared_ptr<NumMwmIds> ids=std::make_shared<NumMwmIds>();
  m2::RectD bounds;
  std::unique_ptr<IndexRouter> router;

  Impl(platform::LocalCountryFile const & input,std::string const & cache,OfflineWalkingProvider::SnapshotMode mode,uint64_t copyByteBudget):directory(cache),name(input.GetCountryName()){
    if(mode!=OfflineWalkingProvider::SnapshotMode::HardLink&&mode!=OfflineWalkingProvider::SnapshotMode::PrivateCopy)throw std::runtime_error("walking snapshot mode unavailable");
    if(name.empty()||name.size()>200||name=="."||name==".."||name.find_first_of("/\\")!=std::string::npos||name.find('\0')!=std::string::npos)
      throw std::runtime_error("walking map unavailable");
    original=fs::absolute(input.GetPath(MapFileType::Map)).lexically_normal();
    if(!fs::is_regular_file(original)||fs::is_symlink(original))throw std::runtime_error("walking map unavailable");
    snapshot=directory.path/(name+".mwm");
    if(mode==OfflineWalkingProvider::SnapshotMode::PrivateCopy){
      copiedSource=std::make_unique<PinnedCopySource>(original,copyByteBudget);
      copiedSource->CopyTo(snapshot);
    }else{
      // Zero-copy inode alias. Cross-filesystem/no-hardlink refusal is explicit; no silent copy.
      fs::create_hard_link(original,snapshot);
    }
    bytes=fs::file_size(snapshot);modified=fs::last_write_time(snapshot);
    if(bytes==0||bytes>kMaxMapBytes)throw std::runtime_error("walking map bound exceeded");
    Digest digest;static char const domain[]="DMaps walking map snapshot BLAKE2b-256 v1";digest.Bytes(domain,sizeof(domain));digest.Text(name);digest.U64(bytes);
    FileReader reader(snapshot.string());if(reader.Size()!=bytes)throw std::runtime_error("walking map changed while opening");std::array<uint8_t,65536> block{};
    for(uint64_t at=0;at<reader.Size();){size_t count=static_cast<size_t>(std::min<uint64_t>(block.size(),reader.Size()-at));reader.Read(at,block.data(),count);digest.Bytes(block.data(),count);at+=count;}
    identity=digest.Finish();
    platform::LocalCountryFile local(directory.path.string(),input.GetCountryFile(),input.GetVersion());local.SyncWithDisk();
    auto registered=source.RegisterMap(local);
    if(registered.second!=MwmSet::RegResult::Success)throw std::runtime_error("walking map unavailable");
    held=source.GetMwmHandleById(registered.first);
    if(!held.IsAlive()||!held.GetValue()->m_cont.IsExist("routing"))throw std::runtime_error("walking routing data unavailable");
    bounds=held.GetValue()->GetHeader().GetBounds();bounds.Inflate(1e-5,1e-5); // Header quantization only, not access walking.
    ids->RegisterFile(local.GetCountryFile());auto tree=std::make_unique<m4::Tree<NumMwmId>>();tree->Add(ids->GetId(local.GetCountryFile()),bounds);
    router=std::make_unique<IndexRouter>(VehicleType::Pedestrian,true,
      [](std::string const &){return std::string();},[this](m2::PointD const & p){return bounds.IsPointInside(p)?name:std::string();},
      [this](std::string const &){return bounds;},ids,std::move(tree),traffic,source);
    if(!Current())throw std::runtime_error("walking map changed while opening");
  }
  bool Current()const{
    try{return (copiedSource?copiedSource->Current(original):fs::equivalent(original,snapshot))&&fs::file_size(snapshot)==bytes&&fs::last_write_time(snapshot)==modified;}
    catch(...){return false;}
  }
};

OfflineWalkingProvider::OfflineWalkingProvider(platform::LocalCountryFile const & map,std::string const & cache,SnapshotMode mode,uint64_t copyByteBudget){
  base::ScopedThreadLogSilencer quiet;
  try{m_impl=std::make_unique<Impl>(map,cache,mode,copyByteBudget);}catch(...){throw std::runtime_error("offline walking map snapshot unavailable");}
}
OfflineWalkingProvider::~OfflineWalkingProvider(){
  base::ScopedThreadLogSilencer quiet;
  // Reset under the guard; implicit member destruction occurs after destructor locals retire.
  m_impl.reset();
}
std::string const & OfflineWalkingProvider::GetMapSnapshotId()const{return m_impl->identity;}
bool OfflineWalkingProvider::IsCurrent(std::string const & expected)const{base::ScopedThreadLogSilencer quiet;return expected==m_impl->identity&&m_impl->Current();}

OfflineWalkingProvider::Result OfflineWalkingProvider::Query(m2::PointD const & from,m2::PointD const & to,
    int64_t departure,std::string const & expected,RouterDelegate const & external){
  base::ScopedThreadLogSilencer quiet;
  if(!Valid(from)||!Valid(to)||from==to||departure<0||departure>kMaxDeparture||static_cast<uint64_t>(departure)>static_cast<uint64_t>(std::numeric_limits<time_t>::max()))return Failure(Status::InvalidRequest);
  if(expected!=m_impl->identity||!m_impl->Current())return Failure(Status::MapChanged);
  if(external.IsCancelled())return Failure(CancelStatus(external));
  if(!m_impl->bounds.IsPointInside(from)||!m_impl->bounds.IsPointInside(to))return Failure(Status::OutsideMap);
  if(mercator::DistanceOnEarth(from,to)>5000)return Failure(Status::LimitReached);
  try{
    RouterDelegate delegate;delegate.SetTimeout(5);
    // Observe caller cancellation at native callbacks and final publication without changing its deadline.
    delegate.SetProgressCallback([&](float){if(external.IsCancelled())delegate.Cancel();});
    delegate.SetPointCheckCallback([&](ms::LatLon const &){if(external.IsCancelled())delegate.Cancel();});
    m_impl->router->SetCurrentTimeGetter([departure]{return static_cast<time_t>(departure);});
    Route route("dmaps-offline-walking",0);route.SetRoutingSettings(GetRoutingSettings(VehicleType::Pedestrian));
    auto code=m_impl->router->CalculateRoute(Checkpoints(from,to),m2::PointD::Zero(),false,delegate,route);
    if(external.IsCancelled())return Failure(CancelStatus(external));
    if(delegate.IsCancelled())return Failure(CancelStatus(delegate));
    if(!m_impl->Current())return Failure(Status::MapChanged);
    if(code==RouterResultCode::StartPointNotFound||code==RouterResultCode::EndPointNotFound||code==RouterResultCode::RouteNotFound||code==RouterResultCode::NeedMoreMaps)return Failure(Status::NoPath);
    if(code==RouterResultCode::Cancelled)return Failure(Status::Cancelled);
    if(code!=RouterResultCode::NoError||!route.IsValid())return Failure(Status::Unavailable);
    double seconds=route.GetTotalTimeSec(),distance=route.GetTotalDistanceMeters();
    if(!std::isfinite(seconds)||!std::isfinite(distance)||seconds<=0||distance<=0)return Failure(Status::Unavailable);
    if(seconds>3600||route.GetPoly().GetSize()>kMaxPoints)return Failure(Status::LimitReached);
    for(auto const & p:route.GetPoly().GetPoints())if(!Valid(p))return Failure(Status::Unavailable);
    Result result;result.status=Status::Ok;result.mapSnapshotId=m_impl->identity;result.estimatedSeconds=static_cast<uint32_t>(std::ceil(seconds));result.distanceMeters=distance;result.geometry=route.GetPoly().GetPoints();
    // Consume the pedestrian router's actual maneuvers, not angles inferred from the outline.
    // Instructions are optional: malformed/over-bound guidance cannot manufacture partial advice.
    bool guidance=true;uint32_t previous=0;double previousMeters=-1;
    for(auto const & segment:route.GetRouteSegments()){
      auto const & turn=segment.GetTurn();auto direction=static_cast<uint32_t>(turn.m_pedestrianTurn);
      if(direction==0)continue;
      double meters=segment.GetDistFromBeginningMeters();
      if(direction>4||turn.m_index>=result.geometry.size()||(direction==4&&turn.m_index+1!=result.geometry.size())||(!result.directions.empty()&&turn.m_index<=previous)||
         !std::isfinite(meters)||meters<0||meters<previousMeters||meters>distance+0.001||result.directions.size()==256){guidance=false;break;}
      result.directions.push_back({turn.m_index,direction,meters});previous=turn.m_index;previousMeters=meters;
    }
    result.directionsAvailable=guidance&&!result.directions.empty()&&result.directions.back().maneuver==4&&
        result.directions.back().pointIndex+1==result.geometry.size();
    if(!result.directionsAvailable)result.directions.clear();
    Digest token;static char const domain[]="DMaps walking path BLAKE2b-256 v2";token.Bytes(domain,sizeof(domain));token.Text(result.mapSnapshotId);token.U64(static_cast<uint64_t>(departure));token.Number(from.x);token.Number(from.y);token.Number(to.x);token.Number(to.y);token.U64(result.estimatedSeconds);token.Number(distance);token.U64(result.geometry.size());
    for(auto const & p:result.geometry){token.Number(p.x);token.Number(p.y);}
    token.U64(result.directionsAvailable?1:0);token.U64(result.directions.size());
    for(auto const & d:result.directions){token.U64(d.pointIndex);token.U64(d.maneuver);token.Number(d.distanceFromStartMeters);}result.pathToken=token.Finish();
    // Final cooperative publication fence, after all geometry copying/hashing and filesystem work.
    // This does not recall a result after return or preempt an in-progress native operation.
    if(!m_impl->Current())return Failure(Status::MapChanged);
    if(external.IsCancelled())return Failure(CancelStatus(external));
    if(delegate.IsCancelled())return Failure(CancelStatus(delegate));
    return result;
  }catch(...){return Failure(Status::Unavailable);}
}
}
