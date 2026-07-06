#include "botcraft/Game/Entities/entities/AgeableMobEntity.hpp"

#include <mutex>

namespace Botcraft
{
    const std::array<std::string, AgeableMobEntity::metadata_count> AgeableMobEntity::metadata_names{ {
        "data_baby_id",
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        "age_locked",
#endif
    } };

    AgeableMobEntity::AgeableMobEntity()
    {
        // Initialize all metadata with default values
        SetDataBabyId(false);
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        SetAgeLocked(false);
#endif
    }

    AgeableMobEntity::~AgeableMobEntity()
    {

    }

    bool AgeableMobEntity::IsAgeableMob() const
    {
        return true;
    }


    ProtocolCraft::Json::Value AgeableMobEntity::Serialize() const
    {
        ProtocolCraft::Json::Value output = PathfinderMobEntity::Serialize();

        output["metadata"]["data_baby_id"] = GetDataBabyId();
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        output["metadata"]["age_locked"] = GetAgeLocked();
#endif

        return output;
    }


    void AgeableMobEntity::SetMetadataValue(const int index, const std::any& value)
    {
        if (index < hierarchy_metadata_count)
        {
            PathfinderMobEntity::SetMetadataValue(index, value);
        }
        else if (index - hierarchy_metadata_count < metadata_count)
        {
            std::scoped_lock<std::shared_mutex> lock(entity_mutex);
            metadata[metadata_names[index - hierarchy_metadata_count]] = value;
        }
    }

    bool AgeableMobEntity::GetDataBabyId() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<bool>(metadata.at("data_baby_id"));
    }

#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    bool AgeableMobEntity::GetAgeLocked() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<bool>(metadata.at("age_locked"));
    }
#endif


    void AgeableMobEntity::SetDataBabyId(const bool data_baby_id)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["data_baby_id"] = data_baby_id;
    }

#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    void AgeableMobEntity::SetAgeLocked(const bool age_locked)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["age_locked"] = age_locked;
    }
#endif

}
