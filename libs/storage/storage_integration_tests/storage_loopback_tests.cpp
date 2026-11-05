// Authored DMaps regression scenarios; no donor source or cartographic bytes copied.
#include "testing/testing.hpp"
#include "storage/storage.hpp"
#include "storage/http_map_files_downloader.hpp"
#include "platform/downloader_utils.hpp"
#include "platform/http_request.hpp"
#include "platform/local_country_file.hpp"
#include "platform/platform.hpp"
#include "platform/platform_tests_support/writable_dir_changer.hpp"
#include "coding/file_reader.hpp"
#include "coding/sha1.hpp"
#include "base/file_name_utils.hpp"
#include "base/logging.hpp"
#include "defines.hpp"
#include "std/target_os.hpp"
#ifdef OMIM_OS_MAC
#include <CoreFoundation/CoreFoundation.h>
#endif
#include <memory>
#include <string>
#include <vector>

namespace
{
// Apple's actual HTTP implementation delivers on the main dispatch queue. The
// offscreen Qt plugin does not pump it; service CF directly without a GUI window.
void RunOwnedEventLoop()
{
#ifdef OMIM_OS_MAC
  CFRunLoopRun();
#else
  testing::RunEventLoop();
#endif
}
void StopOwnedEventLoop()
{
#ifdef OMIM_OS_MAC
  CFRunLoopStop(CFRunLoopGetMain());
#else
  testing::StopEventLoop();
#endif
}
using namespace storage;
using downloader::HttpRequest;
using Runner = Platform::ThreadRunner;
constexpr int64_t kSize = 131072;
constexpr int64_t kChunk = 16384;
char const kHash[] = "+CYCjtRysfre3b9U/BkSoJXSh5U=";
char const kServer[] = "http://127.0.0.1:34568";

std::string Url(std::string const & name)
{
  return std::string(kServer) + "/" + downloader::GetFileDownloadUrl(name + ".mwm", 991231);
}

void CheckBytes(std::string const & path)
{
  FileReader reader(path);
  TEST_EQUAL(reader.Size(), kSize, (path));
  std::vector<unsigned char> bytes(kSize);
  reader.Read(0, bytes.data(), bytes.size());
  for (size_t i = 0; i < bytes.size(); ++i)
    TEST_EQUAL(bytes[i], static_cast<unsigned char>(i % 256), (i));
  TEST_EQUAL(coding::SHA1::CalculateBase64(path), kHash, (path));
}

std::string Countries()
{
  return std::string(R"({"v":991231,"id":"Countries","g":[{"id":"Owned","g":[
    {"id":"Good","s":131072,"sha1_base64":")") + kHash +
    R"("},{"id":"Corrupt","s":131072,"sha1_base64":")" + kHash + R"("}]}]})";
}

// Request/controller recreation, NOT process restart and NOT Storage queue restoration.
UNIT_TEST(DMapsLoopback_RequestResumeExactBytes)
{
  WritableDirChanger writable("dmaps-loopback-request", WritableDirChanger::SettingsDirPolicy::UseWritableDir);
  auto const path = base::JoinPath(GetPlatform().WritableDir(), "Resume.mwm.ready");
  bool interrupted = false;
  bool finished = false;
  std::unique_ptr<HttpRequest> request(HttpRequest::GetFile({Url("Resume")}, path, kSize,
    [&](HttpRequest &) { finished = true; StopOwnedEventLoop(); },
    [&](HttpRequest & r)
    {
      if (r.GetProgress().m_bytesDownloaded > 0 && r.GetProgress().m_bytesDownloaded < kSize)
      {
        interrupted = true;
        StopOwnedEventLoop();
      }
    }, kChunk, false /* preserve progress explicitly */));
  RunOwnedEventLoop();
  TEST(interrupted, ());
  TEST(!finished, ());
  request.reset(); // Delete native HTTP threads and close writer before recreation.
  TEST(GetPlatform().IsFileExistsByFullPath(path + RESUME_FILE_EXTENSION), ());
  TEST(GetPlatform().IsFileExistsByFullPath(path + DOWNLOADING_FILE_EXTENSION), ());
  TEST(!GetPlatform().IsFileExistsByFullPath(path), ());

  request.reset(HttpRequest::GetFile({Url("Resume")}, path, kSize,
    [&](HttpRequest & r)
    {
      TEST_EQUAL(r.GetStatus(), downloader::DownloadStatus::Completed, ());
      finished = true;
      StopOwnedEventLoop();
    }, {}, kChunk, false));
  // This is loaded by the real constructor, before any new response is dispatched.
  TEST_GREATER(request->GetProgress().m_bytesDownloaded, 0, ());
  TEST_LESS(request->GetProgress().m_bytesDownloaded, kSize, ());
  RunOwnedEventLoop();
  TEST(finished, ());
  request.reset();
  CheckBytes(path);
  TEST(!GetPlatform().IsFileExistsByFullPath(path + RESUME_FILE_EXTENSION), ());
  TEST(!GetPlatform().IsFileExistsByFullPath(path + DOWNLOADING_FILE_EXTENSION), ());
}

UNIT_CLASS_TEST(Runner, DMapsLoopback_StorageIntegrityAndIdempotence)
{
  WritableDirChanger writable("dmaps-loopback-storage", WritableDirChanger::SettingsDirPolicy::UseWritableDir);
  std::string previousServer = GetPlatform().CustomMapServerUrl();
  std::string ownedServer(kServer);
  GetPlatform().SetCustomMapServerUrl(ownedServer);
  struct RestoreServer { std::string previous; ~RestoreServer() { GetPlatform().SetCustomMapServerUrl(previous); } } restore{previousServer};
  Storage storage(Countries(), std::make_unique<HttpMapFilesDownloader>());
  storage.SetDownloadingServersForTesting({kServer});
  storage.SetEnabledIntegrityValidationForTesting(true);
  unsigned updates = 0;
  bool goodTerminal = false;
  bool corruptTerminal = false;
  storage.Init([&](CountryId const & id, LocalFilePtr const file)
  {
    TEST_EQUAL(id, "Good", ());
    ++updates;
    CheckBytes(file->GetPath(MapFileType::Map));
  }, [](CountryId const &, LocalFilePtr const) { return false; });
  storage.Subscribe([&](CountryId const & id)
  {
    NodeAttrs attrs;
    storage.GetNodeAttrs(id, attrs);
    // Queue emptiness is insufficient: the file-thread SHA1 task may still be pending.
    if (id == "Good" && attrs.m_status == NodeStatus::OnDisk)
    {
      goodTerminal = true;
      StopOwnedEventLoop();
    }
    if (id == "Corrupt" && attrs.m_status == NodeStatus::Error)
    {
      corruptTerminal = true;
      StopOwnedEventLoop();
    }
  }, [](CountryId const &, downloader::Progress const &) {});

  storage.DownloadNode("Good");
  RunOwnedEventLoop();
  TEST(goodTerminal, ());
  TEST_EQUAL(updates, 1, ());
  storage.DownloadNode("Good");
  TEST(!storage.IsDownloadInProgress(), ());
  TEST_EQUAL(updates, 1, ());

  // Expected production integrity error must not abort the native test harness;
  // terminal status, no registration and bad-file cleanup remain asserted below.
  base::ScopedLogAbortLevelChanger expectedIntegrityError(base::LCRITICAL);
  storage.DownloadNode("Corrupt");
  RunOwnedEventLoop();
  TEST(corruptTerminal, ());
  TEST_EQUAL(updates, 1, ("Corrupt payload must never reach the registration callback"));
  TEST(!storage.GetLatestLocalFile("Corrupt"), ());
  auto const corrupt = base::JoinPath(GetPlatform().WritableDir(), "991231", "Corrupt.mwm");
  for (auto const & suffix : {"", ".ready", ".ready.resume", ".ready.downloading"})
    TEST(!GetPlatform().IsFileExistsByFullPath(corrupt + suffix), (suffix));
  CheckBytes(storage.GetLatestLocalFile("Good")->GetPath(MapFileType::Map));
  // Terminal notifications have followed SHA validation; no pending validation captures Storage.
}

UNIT_CLASS_TEST(Runner, DMapsLoopback_GroupDeletePreservesSiblingAndRepeatsSafely)
{
  WritableDirChanger writable("dmaps-loopback-group", WritableDirChanger::SettingsDirPolicy::UseWritableDir);
  std::string previousServer = GetPlatform().CustomMapServerUrl();
  std::string ownedServer(kServer);GetPlatform().SetCustomMapServerUrl(ownedServer);
  struct RestoreServer { std::string previous; ~RestoreServer() { GetPlatform().SetCustomMapServerUrl(previous); } } restore{previousServer};
  auto leaf = [](std::string const & name) {
    return std::string("{\"id\":\"") + name + "\",\"s\":131072,\"sha1_base64\":\"" + kHash + "\"}";
  };
  auto countries = std::string("{\"v\":991231,\"id\":\"Countries\",\"g\":[{\"id\":\"Owned\",\"g\":[")
      + leaf("GroupA") + "," + leaf("GroupB") + "]}," + leaf("Keep") + "]}";
  Storage storage(countries, std::make_unique<HttpMapFilesDownloader>());
  storage.SetDownloadingServersForTesting({kServer});storage.SetEnabledIntegrityValidationForTesting(true);
  unsigned updated = 0, deleted = 0;bool groupReady = false, keepReady = false;
  storage.Init([&](CountryId const &, LocalFilePtr const file) { ++updated;CheckBytes(file->GetPath(MapFileType::Map)); },
    [&](CountryId const & id, LocalFilePtr const file) {
      TEST(id == "GroupA" || id == "GroupB", (id));TEST(file != nullptr, (id));++deleted;return false;
    });
  storage.Subscribe([&](CountryId const & id) {
    NodeAttrs attrs;storage.GetNodeAttrs(id, attrs);
    if (id == "Owned" && attrs.m_status == NodeStatus::OnDisk) { groupReady = true;StopOwnedEventLoop(); }
    if (id == "Keep" && attrs.m_status == NodeStatus::OnDisk) { keepReady = true;StopOwnedEventLoop(); }
  }, [](CountryId const &, downloader::Progress const &) {});
  storage.DownloadNode("Owned");RunOwnedEventLoop();TEST(groupReady, ());TEST_EQUAL(updated, 2, ());
  storage.DownloadNode("Keep");RunOwnedEventLoop();TEST(keepReady, ());TEST_EQUAL(updated, 3, ());
  auto keep = storage.GetLatestLocalFile("Keep");TEST(keep != nullptr, ());auto keepPath = keep->GetPath(MapFileType::Map);
  CheckBytes(keepPath);
  storage.DeleteNode("Owned");TEST_EQUAL(deleted, 2, ());
  NodeAttrs group;storage.GetNodeAttrs("Owned", group);TEST_EQUAL(group.m_status, NodeStatus::NotDownloaded, ());
  for (auto const & id : {"GroupA", "GroupB"}) {
    TEST(!storage.GetLatestLocalFile(id), (id));
    TEST_EQUAL(storage.CountryStatusEx(id), Status::NotDownloaded, (id));
    auto path = base::JoinPath(GetPlatform().WritableDir(), "991231", std::string(id) + ".mwm");
    for (auto const & suffix : {"", ".ready", ".ready.resume", ".ready.downloading"})
      TEST(!GetPlatform().IsFileExistsByFullPath(path + suffix), (id, suffix));
  }
  TEST_EQUAL(storage.CountryStatusEx("Keep"), Status::OnDisk, ());CheckBytes(keepPath);
  storage.DeleteNode("Owned");TEST_EQUAL(deleted, 2, ());TEST_EQUAL(updated, 3, ());
  TEST(!storage.IsDownloadInProgress(), ());CheckBytes(keepPath);
}
} // namespace
